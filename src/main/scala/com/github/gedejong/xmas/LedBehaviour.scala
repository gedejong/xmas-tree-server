package com.github.gedejong.xmas

import java.awt.Color

import scala.concurrent.duration.FiniteDuration

/**
  * Created by edejong on 12-12-2016.
  */
trait LedBehaviour {

  trait LedCommand

  case class Blink(color: Color) extends LedCommand

  case class Temporary(color: Color, duration: FiniteDuration) extends LedCommand

  case class Permanent(color: Color) extends LedCommand

  case class SendToLed(led: Int, ledCommand: LedCommand)
}
