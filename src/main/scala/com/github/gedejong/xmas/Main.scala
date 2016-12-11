package com.github.gedejong.xmas

import java.awt.Color
import java.lang.Math._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import shapeless.Coproduct

import scala.io.StdIn

object Main extends App {

  import TreeControl._

  implicit val system = ActorSystem("xmas-tree-system")
  implicit val executionContext = system.dispatcher
  val log = Logging(system.eventStream, "MainLogging")
  val decider: Supervision.Decider = { e =>
    log.warning("Unhandled exception in stream", e)
    Supervision.Restart
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider))

  val (minLat, maxLat) = (50f, 54f)
  val ledCount = 46

  def pointToLed(point: Point): Int = {
    val latitude = point.coordinates(1)
    min(ledCount, max(0, round(((latitude.toFloat - minLat) / (maxLat - minLat)) * ledCount)))
  }

  def featureToDazzle(feature: Feature): TreeCommand = {
    val determinedIntensity = min(130, feature.properties.speed.getOrElse(50d) / 130d) / 2d + .5
    Coproduct[TreeCommand](
      SetLed(pointToLed(feature.geometry), new Color(
        (determinedIntensity * 256).toInt,
        (determinedIntensity * 256).toInt,
        (determinedIntensity * 256).toInt)))
  }

  import scala.concurrent.duration._

  val lossenLeds: Flow[Feature, TreeCommand, NotUsed] =
    Flow[Feature].collect {
      case feature if feature.properties.activityString.toLowerCase == "LOSSEN" =>
        pointToLed(feature.geometry)
    }.groupBy(ledCount * 2, identity)
      .flatMapConcat((led: Int) =>
        Source.single(Coproduct[TreeCommand](SetLedTarget(led, Color.RED)))
          .merge(
            Source.single(Coproduct[TreeCommand](SetLedTarget(led, Color.BLACK)))
              .delay(1.seconds, DelayOverflowStrategy.dropHead)))
    .mergeSubstreamsWithParallelism(ledCount * 2)

  val featureToDazzleFlow: Flow[Feature, TreeCommand, NotUsed] =
    Flow[Feature].map(featureToDazzle)

  val g = Source.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val in = Realisations.realisationsFlow

    val bcast = b.add(Broadcast[Feature](2))
    val merge = b.add(Merge[TreeCommand](2))

    in ~> bcast

    bcast ~> featureToDazzleFlow ~> merge
    bcast ~> lossenLeds ~> merge

    SourceShape(merge.out)
  })

  val commandSink: RunnableGraph[ActorRef] =
    Source.actorRef[TreeCommand](1000, OverflowStrategy.dropHead)
      .addAttributes(ActorAttributes.supervisionStrategy(decider))
      .merge(g)
      .log("command", v => v)
      .via(treeCommandEncoder)
      .log("encoded", bs => bs.toVector.mkString(", "))
      .via(treeBinary(args(0)))
      .to(Sink.foreach(p => log.info(s"Received $p")))

  val commandActor = commandSink.run()(materializer)

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
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}
