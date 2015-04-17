package sputnik

import akka.actor.Actor

abstract class EngineRpc
case class PlaceOrder(order: Order) extends EngineRpc
case class CancelOrder(id: Int) extends EngineRpc

// Placeholders
case class Contract(ticker: String)

class Engine(contract: Contract) extends Actor {
  var orderBook = new OrderBook()
  def receive = {
    case PlaceOrder(order) =>
      val (newOrderBook, orders, fills) = orderBook.placeOrder(order)
      orderBook = newOrderBook

      context.actorSelection("*/webserver") ! orderBook
      fills.foreach((x) => context.actorSelection("*/accountant/" + x.aggressiveOrder.username) ! x)
      fills.foreach((x) => context.actorSelection("*/accountant/" + x.passiveOrder.username) ! x)
      orders.foreach((x) => context.actorSelection("*/accountant/" + x.username) ! x)

    case CancelOrder(id) => orderBook.getOrderById(id) match {
      case Some(order) =>
        context.actorSelection("*/accountant/" + order.username) ! order.copy(quantity = 0)
        orderBook = orderBook.cancelOrder(id)
        sender ! true
      case _ => sender ! false
    }
  }

}
