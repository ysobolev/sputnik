package sputnik

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import scala.collection.mutable


class EngineRouter extends Actor with ActorLogging with GetOrCreateChild {
  def childFactory(c: Contract) = new Engine(c)
  def getOrCreateChild = super.getOrCreateChild[Contract, Engine]((c) => new Engine(c))_

  def receive = {
    case PlaceOrder(order) =>
      log.info(s"PlaceOrder($order)")
      this.getOrCreateChild(order.contract) ! PlaceOrder(order)

    case CancelOrder(contract, id) =>
      log.info(s"CancelOrder($contract, $id)")
      this.getOrCreateChild(contract) ! CancelOrder(contract, id)
  }
}
