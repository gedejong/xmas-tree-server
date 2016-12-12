package com.github.gedejong.xmas

import java.awt.Color
import java.lang.Math._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, RunnableGraph, Sink, Source}
import shapeless.Coproduct

import scala.concurrent.Future
import scala.io.StdIn

object Main extends App {
  import TreeControl._
  import CoordOps._
  import LedCoordMapping._

  implicit val system = ActorSystem("xmas-tree-system")
  implicit val executionContext = system.dispatcher
  val log = Logging(system.eventStream, "MainLogging")
  val decider: Supervision.Decider = { e =>
    log.warning("Unhandled exception in stream", e)
    e.printStackTrace()
    Supervision.Restart
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider))

  def featureToDazzle(feature: Feature): TreeCommand = {
    val determinedIntensity = min(130, feature.properties.speed.getOrElse(50d)) / (130d * 3d) + .66
    Coproduct[TreeCommand](
      SetLed(coordToLed(Coordinates.fromPoint(feature.geometry)), new Color(  // TODO Please doublecheck if geometry is truly in lon lat order truly contains lat (and not lon)
        (determinedIntensity * 256).toInt,
        (determinedIntensity * 256).toInt,
        (determinedIntensity * 256).toInt)))
  }

  import scala.concurrent.duration._

  val lossenLeds: Flow[Feature, TreeCommand, NotUsed] =
    Flow[Feature].collect {
      case feature if feature.properties.activityString.toLowerCase == "lossen" =>
        coordToLed(Coordinates.fromPoint(feature.geometry))                 // TODO Please doublecheck if geometry is truly in lon lat order contains lat (and not lon)
    }.groupBy(ledCount * 2, identity)
      .flatMapConcat((led: Int) =>
        Source.single(Coproduct[TreeCommand](SetLedTarget(led, new Color(200, 100, 100))))
          .merge(
            Source.single(Coproduct[TreeCommand](SetLedTarget(led, new Color(0, 0, 150))))
              .delay(1.seconds, DelayOverflowStrategy.dropHead)))
      .mergeSubstreamsWithParallelism(ledCount * 2)

  val featureToDazzleFlow: Flow[Feature, TreeCommand, NotUsed] =
    Flow[Feature].map(featureToDazzle)

  val g = Flow.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val bcast = b.add(Broadcast[Feature](2))
    val merge = b.add(Merge[TreeCommand](2))

    bcast.out(0) ~> featureToDazzleFlow ~> merge.in(0)
    bcast.out(1) ~> lossenLeds ~> merge.in(1)

    FlowShape(bcast.in, merge.out)
  })

  val realisationsFlow: Source[TreeCommand, (Cancellable, HostConnectionPool)] = Realisations.realisationsFlow.via(g)

  val commandSink: RunnableGraph[(ActorRef, (Cancellable, HostConnectionPool))] =
    Source.actorRef[TreeCommand](1000, OverflowStrategy.dropHead)
      .addAttributes(ActorAttributes.supervisionStrategy(decider))
      .mergeMat(realisationsFlow)(Keep.both)
      .log("command", v => v)
      .via(treeCommandEncoder)
      .log("encoded", bs => bs.toVector.mkString(", "))
      .via(treeBinary(args(0)))
      .to(Sink.foreach(p => log.info(s"Received $p")))

  val (commandActor, (realisationsStreamCancellable, connectionPool)) = commandSink.run()(materializer)

  val route =
    pathPrefix("led" / IntNumber) { led =>
      path("color") {
        post {
          parameters(('red.as[Int], 'green.as[Int], 'blue.as[Int])) {
            case (red, green, blue) =>
              complete {
                val command = Coproduct[TreeCommand](SetLed(led, new Color(red, green, blue)))
                commandActor ! command
                HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>Send command $command</h1>")
              }
          } ~
            parameters('color.as[String]) { color =>
              complete {
                val command = Coproduct[TreeCommand](SetLed(led, Color.decode(color)))
                commandActor ! command
                HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>Send command $command</h1>")
              }
            }
        }
      } ~
        path("targetcolor") {
          post {
            parameters(('red.as[Int], 'green.as[Int], 'blue.as[Int])) {
              case (red, green, blue) =>
                complete {
                  val command = Coproduct[TreeCommand](SetLedTarget(led, new Color(red, green, blue)))
                  commandActor ! command
                  HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>Send command $command</h1>")
                }
            }
          }
        }
    } ~
      path("twinkle") {
        post {
          parameters('flicker.as[Int]) { flicker =>
            complete {
              val flicker1 = Coproduct[TreeCommand](SetFlicker(flicker))
              commandActor ! flicker1
              HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>Send command $flicker1</h1>")
            }
          }
        }
      }
  val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8080)

  log.info(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()
  bindingFuture.flatMap(_.unbind()).onComplete { _ =>
    log.info("Cancelling realisations stream")
    Future(realisationsStreamCancellable.cancel())
    log.info("Shutting down actor system")
    system.terminate()
    log.info("Stopping rct connection pool")
    connectionPool.shutdown().onComplete { _ =>
      log.info("Stopped rct connection pool")
      system.terminate()
    }
  }

}
