package sputnik

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.event.LoggingReceive
import com.github.nscala_time.time.Imports._
import sputnik.BookSide._
import sputnik.ContractType._

object Engine {
  case class PlaceOrder(order: Order)
  case class CancelOrder(contract: Contract, id: Int)

  def props(contract: Contract): Props = Props(new Engine(contract))
}


class Engine(contract: Contract) extends Actor with ActorLogging {
  def accountantRouter = context.system.actorSelection("/user/accountant")

  val receive = state(new OrderBook())

  def state(orderBook: OrderBook): Receive = LoggingReceive {
    case Engine.PlaceOrder(order) =>
      log.info(s"PlaceOrder($order)")
      assert(order.contract == contract)
      val (newOrderBook, orders, trades) = orderBook.placeOrder(order)

      trades.foreach((x) => accountantRouter ! Accountant.TradeNotify(x))
      context.become(state(newOrderBook))

    case Engine.CancelOrder(c, id) =>
      log.info(s"CancelOrder($id)")
      assert(c == contract)
      orderBook.getOrderById(id) match {
        case Some(order) =>
          accountantRouter ! Accountant.OrderUpdate(order.copy(quantity = 0))
          context.become(state(orderBook.cancelOrder(id)))
        case None =>
          log.error(s"order $id not found")
      }
  }

}

object Test extends App {
  val system = ActorSystem("sputnik")
  val btc = Contract("BTC", None, None, 1000000, 100000, 100000000, CASH)
  val usd = Contract("USD", None, None, 10000, 100, 1000000, CASH)
  val btcusd = Contract("BTC/USD", Some(usd), Some(btc), 100, 1000000, 1, CASH_PAIR)
  val engineRouter = system.actorOf(Props[EngineRouter], name = "engine")
  val accountantRouter = system.actorOf(Props[AccountantRouter], name = "accountant")
  val ledger = system.actorOf(Props[Ledger], name = "ledger")
  accountantRouter ! Accountant.PlaceOrder(Order(1, 100, 100, DateTime.now, BUY, Account("testA"), btcusd))
  accountantRouter ! Accountant.PlaceOrder(Order(2, 100, 50, DateTime.now, BUY, Account("testB"), btcusd))
  accountantRouter ! Accountant.PlaceOrder(Order(3, 50, 75, DateTime.now, SELL, Account("testC"), btcusd))
  //system.shutdown()
}
