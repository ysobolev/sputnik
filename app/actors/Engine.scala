/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package actors

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.event.LoggingReceive
import akka.pattern._
import akka.util.Timeout
import com.github.nscala_time.time.Imports._
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


object Test extends App {
  val system = ActorSystem("sputnik")
  import models.ContractType._
  import models.BookSide._

  val btc = Contract("BTC", None, None, tickSize = 1000000, lotSize = 100000, denominator = 100000000, CASH)
  val usd = Contract("USD", None, None, tickSize = 10000, lotSize = 100, denominator = 1000000, CASH)
  val btcusd = Contract("BTC/USD", Some(usd), Some(btc), tickSize = 100, lotSize = 1000000, 1, CASH_PAIR)

  class Subscriber extends Actor with ActorLogging {
    // set up subscriptions
    //SputnikEventBus.subscribe(self, GenericClassifier)
    //SputnikEventBus.subscribe(self, OrderBookClassifier())
    //SputnikEventBus.subscribe(self, PostingClassifier())
    SputnikEventBus.subscribe(self, TradeClassifier(None, Set(Account("testB"))))

    def receive = {
      case msg =>
        log.info(s"subscription msg received: ${msg}")
    }

  }
  val subscriber = system.actorOf(Props[Subscriber])

  val engineRouter = system.actorOf(Props[EngineRouter], name = "engine")
  val accountantRouter = system.actorOf(Props[AccountantRouter], name = "accountant")
  val ledger = system.actorOf(Props[Ledger], name = "ledger")

  accountantRouter ! Accountant.DepositCash(Account("testB"), usd, usd.quantityToWire(1000))
  accountantRouter ! Accountant.DepositCash(Account("testA"), usd, usd.quantityToWire(1000))
  val btcq: Quantity = btc.quantityToWire(1000)
  accountantRouter ! Accountant.DepositCash(Account("testC"), btc, btcq)
  Thread.sleep(3000)
  val o1 = Order(btcusd.quantityToWire(1), btcusd.priceToWire(200), DateTime.now, BUY, Account("testA"), btcusd)
  accountantRouter ! Accountant.PlaceOrder(o1)
  accountantRouter ! Accountant.PlaceOrder(Order(btcusd.quantityToWire(1), btcusd.priceToWire(100), DateTime.now, BUY, Account("testB"), btcusd))
  accountantRouter ! Accountant.PlaceOrder(Order(btcusd.quantityToWire(0.5), btcusd.priceToWire(150), DateTime.now, SELL, Account("testC"), btcusd))
  import java.util.concurrent.TimeUnit
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)
  Thread.sleep(3000)
  val positionsA = accountantRouter ? Accountant.GetPositions(Account("testA"))
  positionsA.mapTo[Accountant.PositionsMsg].foreach(println)

  val positionsB = accountantRouter ? Accountant.GetPositions(Account("testB"))
  positionsB.mapTo[Accountant.PositionsMsg].foreach(println)

  val positionsC = accountantRouter ? Accountant.GetPositions(Account("testC"))
  positionsC.mapTo[Accountant.PositionsMsg].foreach(println)

  val cash_pos = accountantRouter ? Accountant.GetPositions(Account("cash", LedgerSide.ASSET))
  cash_pos.mapTo[Accountant.PositionsMsg].foreach(println)
}
