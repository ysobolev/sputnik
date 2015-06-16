package sputnik

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import akka.event.LoggingReceive
import scala.collection.mutable


class EngineRouter extends Actor with ActorLogging with GetOrCreateChild {
  implicit def childFactory(c: Contract): Props = Engine.props(c)

  def receive = LoggingReceive {
    case Engine.PlaceOrder(order) =>
      getOrCreateChild(order.contract).tell(Engine.PlaceOrder(order), sender())

    case Engine.CancelOrder(contract, id) =>
      getOrCreateChild(contract).tell(Engine.CancelOrder(contract, id), sender())
  }
}
