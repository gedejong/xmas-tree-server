package com.github.gedejong.xmas

import java.awt.Color

import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.io.IO
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import shapeless.Coproduct

import scala.io.StdIn
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

  val commandSink =
    Source.actorRef[TreeCommand](300, OverflowStrategy.dropHead)
      .addAttributes(ActorAttributes.supervisionStrategy(decider))
      .log("command", v => v)
      .via(treeCommandEncoder)
      .log("encoded", bs => bs.toVector.mkString(", "))
      .via(treeBinary(args(0)))
      .to(Sink.foreach(log.info("Received {}", _)))

  val commandActor = commandSink.run()(materializer)

  val route =
    pathPrefix("led" / IntNumber) { led =>
      path("color") {
        post {
          parameters(('red.as[Int], 'green.as[Int], 'blue.as[Int])) {
            case (red, green, blue) =>
              complete {
                val command = Coproduct[TreeCommand]( SetLed(led, new Color(red, green, blue)))
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
  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  log.info(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}
