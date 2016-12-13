package com.github.gedejong.xmas

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import LedBehaviour._

object LedsActor {
  def props(controller: ActorRef) = Props(classOf[LedsActor], controller)
}

class LedsActor(controller: ActorRef) extends Actor with ActorLogging {
  override def receive: Receive = managing(Map())

  def managing(map: Map[Int, ActorRef]): Receive = {

    case SendToLed(led, command) if map.contains(led) => map(led) ! command

    case SendToLed(led, command) =>
      val newActor = context.actorOf(LedActor.props(led, controller))
      newActor ! command
      context become managing(map + (led -> newActor))

    case Delayed(cmd, delay) => context.system.scheduler.scheduleOnce(delay, self, cmd)(context.system.dispatcher)

    case m =>
      log.warning(s"Unknown message: $m")
  }
}
