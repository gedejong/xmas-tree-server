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
import scala.util.Random

object Main extends App {
  import TreeControl._
  import LedCoordMapping._

  def startupScene(commandActor: ActorRef): Source[TreeCommand, NotUsed] = {
    val startupCommands: Seq[TreeCommand] = Seq(
      (0 until 46).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(0, 0, 0)))),
      (0 to 3).flatMap(_ =>
        Seq(
          // Simacan logo, blink
          Seq(7, 1, 5, 8, 2, 4, 6, 9, 0, 10, 11, 3, 45, 18).map(led => Coproduct[TreeCommand](SetLed(led, new Color(14, 104, 129)))),
          Seq(12, 14, 17, 13, 16, 15, 19, 20, 21, 22, 44).map(led => Coproduct[TreeCommand](SetLed(led, new Color(247, 132, 72)))),
          Seq(25, 26, 23, 27, 24, 28, 29, 30, 32, 43, 31, 33).map(led => Coproduct[TreeCommand](SetLed(led, new Color(255, 201, 99)))),
          Seq(36, 37, 34, 35, 42, 38, 39, 40, 41).map(led => Coproduct[TreeCommand](SetLed(led, new Color(87, 193, 94)))),

            // Simacan logo, permanent
          Seq(7, 1, 5, 8, 2, 4, 6, 9, 0, 10, 11, 3, 45, 18).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(14, 104, 129)))),
          Seq(12, 14, 17, 13, 16, 15, 19, 20, 21, 22, 44).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(247, 132, 72)))),
          Seq(25, 26, 23, 27, 24, 28, 29, 30, 32, 43, 31, 33).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(255, 201, 99)))),
          Seq(36, 37, 34, 35, 42, 38, 39, 40, 41).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(87, 193, 94)))),

          (0 until 46).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(0, 0, 0))))
        ).flatten
      ),
      // Default blue background
      (0 until 46).map(led => Coproduct[TreeCommand](SetLedTarget(led, new Color(100, 240, 255))))
    ).flatten

    Source(startupCommands.to[scala.collection.immutable.Iterable])
  }

  implicit val system = ActorSystem("xmas-tree-system")
  implicit val executionContext = system.dispatcher
  val log = Logging(system.eventStream, "MainLogging")

  private val decider: Supervision.Decider = { e =>
    log.warning("Unhandled exception in stream", e)
    Supervision.Restart
  }

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))

  import LedBehaviour._

  val R = Random

  val featureToLedCommand: Flow[Feature, LedsCommand, NotUsed] =
    Flow[Feature].collect {
      case feature if feature.properties.activityString.toLowerCase == "lossen" =>
        val delay = feature.properties.timestamp.millis
        Delayed(SendToLed(coordToLed(Coordinates.fromPoint(feature.geometry)), Temporary(new Color(255, 100, 255), 3.seconds)), delay)

      case feature if feature.properties.activityString.toLowerCase == "laden" =>
        val delay = feature.properties.timestamp.millis
        Delayed(SendToLed(coordToLed(Coordinates.fromPoint(feature.geometry)), Temporary(new Color(50, 255, 50), 3.seconds)), delay)

      case feature =>
        val determinedIntensity = min(100, feature.properties.speed.getOrElse(50d)) / (100d * 2d) + .5
        val delay = (feature.properties.timestamp + R.nextInt(1000)).millis
        Delayed(SendToLed(coordToLed(Coordinates.fromPoint(feature.geometry)), Blink(
          new Color(
            (determinedIntensity * 256).toInt,
            (determinedIntensity * 256).toInt,
            (determinedIntensity * 50).toInt))), delay)
    }

  val commandSink: RunnableGraph[ActorRef] =
    Source.actorRef[TreeCommand](1000, OverflowStrategy.dropHead)
      .addAttributes(ActorAttributes.supervisionStrategy(decider))
      .log("command", identity)
      .throttle(1000, 1.second, 100, ThrottleMode.shaping)
      .via(treeCommandEncoder)
      .via(treeBinary(args(0)))
      .to(Sink.foreach(p => log.info(s"Received $p")))

  val commandActor = commandSink.run()(materializer)

  commandActor ! Coproduct[TreeCommand](SetFlicker(200))

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

  // Startup scene
  startupScene(commandActor)
    .throttle(1, 25.millis, 1, ThrottleMode.shaping)
    .to(Sink.actorRef(commandActor, None))
    .run()

  log.info(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()

  log.info("Cancelling realisations stream")
  Future(cancellable.cancel())

  bindingFuture.flatMap(_.unbind()).onComplete { _ =>

    println("Stopping rct connection pool")
    hostConnectionPool.shutdown().onComplete { _ =>
      println("Stopped rct connection pool")
      materializer.shutdown()
      system.terminate()
    }
  }
}
