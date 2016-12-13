package com.github.gedejong.xmas

import java.awt.Color

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import shapeless.Coproduct

import scala.concurrent.duration.FiniteDuration

object LedActor {
  def props(led: Int, controller: ActorRef) = Props(classOf[LedActor], led, controller)
}

class LedActor(led: Int, controller: ActorRef) extends Actor with TreeModel {
  import LedBehaviour._

  override def receive = permanent(new Color(30, 0, 100))

  def permanent(permanentColor: Color): Receive = {
    case Blink(color) => doBlink(color)
    case Temporary(temporaryColor, duration) => doTemporary(permanentColor, temporaryColor, duration)
    case Permanent(color) => doPermanent(color)
  }

  def temporary(permanentColor: Color, temporaryColor: Color, cancellable: Cancellable): Receive = {
    case Blink(color) => doBlink(color)

    case Temporary(newTemporaryColor, duration) =>
      cancellable.cancel()
      doTemporary(permanentColor, newTemporaryColor, duration)

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
