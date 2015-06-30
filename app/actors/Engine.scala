/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package actors

import actors.Engine._
import akka.actor._
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
  case class SetAccountantRouter(router: ActorRef)

  def props(contract: Contract): Props = Props(new Engine(contract))

  sealed trait State
  case object Trading extends State
  case object Initializing extends State

  sealed trait Data
  case class Initialized(orderBook: OrderBook, accountantRouter: ActorRef) extends Data
  case object Uninitialized extends Data
}


class Engine(contract: Contract) extends LoggingFSM[State, Data] with Stash {
  startWith(Initializing, Uninitialized)

  when(Initializing) {
    case Event(SetAccountantRouter(router), Uninitialized) =>
      goto(Trading) using Initialized(new OrderBook(contract), router)
    case _ =>
      stash()
      stay
  }

  onTransition {
    case Initializing -> Trading =>
      unstashAll()
  }

  when(Trading) {
    case Event(PlaceOrder(order), data @ Initialized(orderBook: OrderBook, accountantRouter: ActorRef)) =>
      assert(order.contract == contract)
      val (newOrderBook, orders, trades) = orderBook.placeOrder(order)
      sender() ! Accountant.OrderBooked(order)
      trades.foreach((x) => accountantRouter ! Accountant.TradeNotify(x))
      SputnikEventBus.publish(newOrderBook)
      goto(Trading) using data.copy(orderBook = newOrderBook)
    case Event(CancelOrder(c, id), data @ Initialized(orderBook: OrderBook, accountantRouter: ActorRef)) =>
      assert(c == contract)
      orderBook.getOrderById(id) match {
        case Some(order) =>
          accountantRouter ! Accountant.OrderCancelled(order.copy(quantity = 0))
          val newOrderBook = orderBook.cancelOrder(id)
          SputnikEventBus.publish(newOrderBook)
          goto(Trading) using data.copy(orderBook = newOrderBook)
        case None =>
          log.error(s"order $id not found")
          stay using data
      }
  }


  initialize()

}
