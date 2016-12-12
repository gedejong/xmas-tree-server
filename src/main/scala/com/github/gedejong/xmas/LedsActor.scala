package com.github.gedejong.xmas

import akka.actor.{Actor, ActorRef, Props}

object LedsActor {
  def props(controller: ActorRef) = Props(classOf[LedsActor], controller)
}

class LedsActor(controller: ActorRef) extends Actor with LedBehaviour {
  override def receive: Receive = managing(Map())

  def managing(map: Map[Int, ActorRef]): Receive = {

    case SendToLed(led, command) if map.contains(led) => map(led) ! command

    case SendToLed(led, command) =>
      val newActor = context.actorOf(LedActor.props(led, controller))
      newActor ! command
      context become managing(map + (led -> newActor))
  }
}
