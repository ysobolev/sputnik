/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package actors

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.event.LoggingReceive
import akka.pattern._
import akka.util.Timeout
import com.github.nscala_time.time.Imports._
import reactivemongo.api.collections.default.BSONCollection
import scala.concurrent.ExecutionContext.Implicits.global
import reactivemongo.bson._
import models._

object Engine {
  case class PlaceOrder(order: Order)
  case class CancelOrder(contract: Contract, id: BSONObjectID)

  def props(contract: Contract): Props = Props(new Engine(contract))
}


class Engine(contract: Contract) extends Actor with ActorLogging {
  def accountantRouter = context.system.actorSelection("/user/accountant")

  val receive = state(new OrderBook(contract))

  def state(orderBook: OrderBook): Receive = {
    SputnikEventBus.publish(orderBook)

    LoggingReceive {
      case Engine.PlaceOrder(order) =>
        assert(order.contract == contract)
        val (newOrderBook, orders, trades) = orderBook.placeOrder(order)
        sender() ! Accountant.OrderBooked(order)
        trades.foreach((x) => accountantRouter ! Accountant.TradeNotify(x))
        context.become(state(newOrderBook))

      case Engine.CancelOrder(c, id) =>
        assert(c == contract)
        orderBook.getOrderById(id) match {
          case Some(order) =>
            accountantRouter ! Accountant.OrderCancelled(order.copy(quantity = 0))
            context.become(state(orderBook.cancelOrder(id)))
          case None =>
            log.error(s"order $id not found")
        }
    }
  }

}
