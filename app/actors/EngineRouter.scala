package actors

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import akka.event.LoggingReceive
import models._

object EngineRouter {
  def props: Props = Props(new EngineRouter)
}

class EngineRouter extends Actor with ActorLogging with GetOrCreateChild {
  implicit def childFactory(c: Contract): Props = Engine.props(c)

  def receive = LoggingReceive {
    case Engine.PlaceOrder(order) =>
      getOrCreateChild(order.contract).tell(Engine.PlaceOrder(order), sender())

    case Engine.CancelOrder(contract, id) =>
      getOrCreateChild(contract).tell(Engine.CancelOrder(contract, id), sender())
  }
}
