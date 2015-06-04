/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package sputnik

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.event.LoggingReceive
import akka.pattern._
import akka.util.Timeout
import com.github.nscala_time.time.Imports._
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import sputnik.BookSide._
import sputnik.ContractType._
import scala.concurrent.ExecutionContext.Implicits.global

object Engine {
  case class PlaceOrder(order: Order)
  case class CancelOrder(contract: Contract, id: ObjectId)

  def props(contract: Contract): Props = Props(new Engine(contract))
}


class Engine(contract: Contract) extends Actor with ActorLogging {
  def accountantRouter = context.system.actorSelection("/user/accountant")

  val receive = state(new OrderBook())

  def state(orderBook: OrderBook): Receive = LoggingReceive {
    case Engine.PlaceOrder(order) =>
      assert(order.contract == contract)
      val (newOrderBook, orders, trades) = orderBook.placeOrder(order)

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

object Test extends App {
  RegisterJodaTimeConversionHelpers()
  val system = ActorSystem("sputnik")
  val btc = Contract("BTC", None, None, tickSize = 1000000, lotSize = 100000, denominator = 100000000, CASH)
  val usd = Contract("USD", None, None, tickSize = 10000, lotSize = 100, denominator = 1000000, CASH)
  val btcusd = Contract("BTC/USD", Some(usd), Some(btc), tickSize = 100, lotSize = 1000000, 1, CASH_PAIR)
  val engineRouter = system.actorOf(Props[EngineRouter], name = "engine")
  val accountantRouter = system.actorOf(Props[AccountantRouter], name = "accountant")
  val ledger = system.actorOf(Props[Ledger], name = "ledger")
  val o1 = Order(btcusd.quantityToWire(1), btcusd.priceToWire(200), DateTime.now, BUY, Account("testA"), btcusd)
  accountantRouter ! Accountant.PlaceOrder(o1)
  accountantRouter ! Accountant.PlaceOrder(Order(btcusd.quantityToWire(1), btcusd.priceToWire(100), DateTime.now, BUY, Account("testB"), btcusd))
  accountantRouter ! Accountant.PlaceOrder(Order(btcusd.quantityToWire(0.5), btcusd.priceToWire(150), DateTime.now, SELL, Account("testC"), btcusd))
  import java.util.concurrent.TimeUnit
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)
  Thread.sleep(1000)
  val positions = accountantRouter ? Accountant.GetPositions(Account("testA"))
  positions.mapTo[Accountant.PositionsMsg].foreach(println)

  val positions2 = accountantRouter ? Accountant.GetPositions(Account("testC"))
  positions2.mapTo[Accountant.PositionsMsg].foreach(println)
  println(BUY.toString)
  //system.shutdown()
  val dbObj = o1.toMongo
  println(dbObj)
  val ordersColl = MongoFactory.database("orders")
  //ordersColl.insert(dbObj)

  val oR = ordersColl.findOne().get
  val o = Order.fromMongo(oR)
  println(o)
}
