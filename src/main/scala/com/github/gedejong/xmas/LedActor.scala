package com.github.gedejong.xmas

import java.awt.Color

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import shapeless.Coproduct

import scala.concurrent.duration.FiniteDuration

object LedActor {
  def props(led: Int, controller: ActorRef) = Props(classOf[LedActor], led, controller)
}

object ExtraColor {
  implicit class PimpedTuple( tuple: (Int, Int, Int)) {
    def asColor = new Color(tuple._1, tuple._2, tuple._3)
  }

  implicit class PimpedColor(color: Color) {
    def asTuple = (color.getRed, color.getGreen, color.getBlue)

    def +(addingColor: Color): Color =
      new Color(
        Math.min(255, color.getRed + addingColor.getRed),
        Math.min(255, color.getGreen + addingColor.getGreen),
        Math.min(255, color.getBlue + addingColor.getBlue))
  }
}

class LedActor(led: Int, controller: ActorRef) extends Actor with TreeModel {
  import LedBehaviour._
  import ExtraColor._

  override def receive = permanent(new Color(20, 75, 90))

  def permanent(permanentColor: Color): Receive = {
    case Blink(color) => doBlink(color)
    case Temporary(temporaryColor, duration) => doTemporary(permanentColor, temporaryColor, duration)
    case Permanent(color) => doPermanent(color)
  }

  def temporary(permanentColor: Color, temporaryColor: Color, cancellable: Cancellable): Receive = {
    case Blink(color) => doBlink(color)

    case Temporary(newTemporaryColor, duration) =>
      cancellable.cancel()
      doTemporary(permanentColor, temporaryColor + newTemporaryColor, duration)

    case Permanent(color) => doPermanent(color)
  }

  private def doPermanent(color: Color) = {
    controller ! Coproduct[TreeCommand](SetLedTarget(led, color))
    context.become(permanent(color))
  }

  private def doTemporary(permanentColor: Color, temporaryColor: Color, duration: FiniteDuration) = {
    val cancellable = context.system.scheduler.scheduleOnce(duration, self, Permanent(permanentColor))(context.system.dispatcher)
    context.become(temporary(permanentColor, temporaryColor, cancellable))
    controller ! Coproduct[TreeCommand](SetLedTarget(led, temporaryColor))
  }

  private def doBlink(color: Color) = {
    controller ! Coproduct[TreeCommand](SetLed(led, color))
  }
}
