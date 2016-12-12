package com.github.gedejong.xmas

import java.awt.Color
import java.lang.Math._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream._
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import shapeless.Coproduct

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn

object Main extends App with LedBehaviour {

  import TreeControl._

  implicit val system = ActorSystem("xmas-tree-system")
  implicit val executionContext = system.dispatcher
  val log = Logging(system.eventStream, "MainLogging")

  val decider: Supervision.Decider = { e =>
    log.warning("Unhandled exception in stream", e)
    Supervision.Restart
  }

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))

  val (minLat, maxLat) = (50.5f, 53f)
  val ledCount = 46

  def pointToLed(point: Point): Int = {
    val latitude = point.coordinates(1)
    min(ledCount, max(0, round(((latitude.toFloat - minLat) / (maxLat - minLat)) * ledCount)))
  }

  val featureToLedCommand: Flow[Feature, SendToLed, NotUsed] =
    Flow[Feature].collect {
      case feature if feature.properties.activityString.toLowerCase == "lossen" =>
        SendToLed(pointToLed(feature.geometry), Temporary(new Color(200, 100, 100), 2.seconds))

      case feature if feature.properties.activityString.toLowerCase == "laden" =>
        SendToLed(pointToLed(feature.geometry), Temporary(new Color(200, 200, 100), 2.seconds))

      case feature =>
        val determinedIntensity = min(130, feature.properties.speed.getOrElse(50d)) / (130d * 3d) + .66
        SendToLed(pointToLed(feature.geometry), Blink(
          new Color(
            (determinedIntensity * 256).toInt,
            (determinedIntensity * 256).toInt,
            (determinedIntensity * 256).toInt)))
    }

  val commandSink: RunnableGraph[ActorRef] =
    Source.actorRef[TreeCommand](1000, OverflowStrategy.dropHead)
      .addAttributes(ActorAttributes.supervisionStrategy(decider))
      .log("command", v => v)
      .via(treeCommandEncoder)
      .via(treeBinary(args(0)))
      .to(Sink.foreach(p => log.info(s"Received $p")))

  val commandActor = commandSink.run()(materializer)

  val ledsActor = system.actorOf(LedsActor.props(commandActor))

  val (cancellable, hostConnectionPool) =
    Realisations.realisationsFlow
      .via(featureToLedCommand)
      .to(Sink.actorRef(ledsActor, PoisonPill)).run()(materializer)

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
    Future(cancellable.cancel())
    log.info("Shutting down actor system")
    system.terminate()
    log.info("Stopping rct connection pool")
    hostConnectionPool.shutdown().onComplete { _ =>
      log.info("Stopped rct connection pool")
      system.terminate()
    }
  }

}
