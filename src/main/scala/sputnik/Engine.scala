package sputnik

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import com.github.nscala_time.time.Imports._
import sputnik.BookSide._
import sputnik.ContractType._

abstract class EngineMessage
case class PlaceOrder(order: Order) extends EngineMessage
case class CancelOrder(id: Int) extends EngineMessage

// Placeholders
case class Contract(ticker: String, denominated: Option[Contract], payout: Option[Contract], tickSize: Int, lotSize: Int, denominator: Int,
                     contractType: ContractType) {
  contractType match {
    case CASH =>
    case _ => if (payout.isEmpty || denominated.isEmpty) throw new Exception("Can't create non-CASH without denominated and payout")
  }

  def getCashSpent(price: Int, quantity: Int): Int = {
    contractType match {
      case CASH_PAIR => (quantity * price / (denominator * payout.get.denominator))
      case _ => quantity * price * lotSize / denominator
    }
  }

}

class Engine(contract: Contract) extends Actor with ActorLogging {
  var orderBook = new OrderBook()
  def accountantRouter = context.system.actorSelection("/user/accountant")

  def receive = {
    case PlaceOrder(order) =>
      assert(order.contract == contract)
      log.info(s"PlaceOrder($order)")
      val (newOrderBook, orders, trades) = orderBook.placeOrder(order)
      orderBook = newOrderBook

      //context.actorSelection("../webserver") ! orderBook
      trades.foreach((x) => accountantRouter ! TradeNotify(x))
      orders.foreach((x) => accountantRouter ! OrderUpdate(x))

    case CancelOrder(id) =>
      log.info(s"CancelOrder($id)")
      orderBook.getOrderById(id) match {
        case Some(order) =>
          accountantRouter ! OrderUpdate(order.copy(quantity = 0))
          orderBook = orderBook.cancelOrder(id)
          sender ! true
        case _ => sender ! false
      }
  }
}

object Test extends App {
  val system = ActorSystem("sputnik")
  val btc = Contract("BTC", None, None, 1000000, 100000, 100000000, CASH)
  val usd = Contract("USD", None, None, 10000, 100, 1000000, CASH)
  val btcusd = Contract("BTC/USD", Some(usd), Some(btc), 100, 1000000, 1, CASH_PAIR)
  val engine = system.actorOf(Props(new Engine(btcusd)), name = "engine")
  val accountantRouter = system.actorOf(Props(new AccountantRouter), name = "accountant")
  val ledger = system.actorOf(Props(new Ledger), name = "ledger")
  engine ! PlaceOrder(Order(1, 100, 100, DateTime.now, BUY, Account("testA"), btcusd))
  //engine ! PlaceOrder(Order(2, 100, 50, DateTime.now, BUY, User("testB"), btcusd))
  engine ! PlaceOrder(Order(3, 50, 75, DateTime.now, SELL, Account("testC"), btcusd))
  //system.shutdown()
}
