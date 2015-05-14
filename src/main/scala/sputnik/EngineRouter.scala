package sputnik

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import scala.collection.mutable


class EngineRouter extends Actor with ActorLogging with GetOrCreateChild {
  implicit def childFactory(c: Contract): Props = Engine.props(c)

  def receive = {
    case Engine.PlaceOrder(order) =>
      log.info(s"PlaceOrder($order)")
      getOrCreateChild(order.contract) ! Engine.PlaceOrder(order)

    case Engine.CancelOrder(contract, id) =>
      log.info(s"CancelOrder($contract, $id)")
      getOrCreateChild(contract) ! Engine.CancelOrder(contract, id)
  }
}
