package com.github.gedejong.xmas

import java.awt.Color

import scala.concurrent.duration.FiniteDuration

object LedBehaviour {

  trait LedCommand

  case class Blink(color: Color) extends LedCommand

  case class Temporary(color: Color, duration: FiniteDuration) extends LedCommand

  case class Permanent(color: Color) extends LedCommand

  sealed trait LedsCommand

  case class SendToLed(led: Int, ledCommand: LedCommand) extends LedsCommand

  case class Delayed(sendToLed: SendToLed, duration: FiniteDuration) extends LedsCommand
}
