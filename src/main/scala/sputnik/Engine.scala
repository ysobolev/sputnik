package sputnik

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import com.github.nscala_time.time.Imports._
import sputnik.sputnik.BookSide._

abstract class EngineMessage
case class PlaceOrder(order: Order) extends EngineMessage
case class CancelOrder(id: Int) extends EngineMessage

// Placeholders
case class Contract(ticker: String)

class Engine(contract: Contract) extends Actor with ActorLogging {
  var orderBook = new OrderBook()
  def accountantRouter = context.system.actorSelection("/user/accountant")

  def receive = {
    case PlaceOrder(order) =>
      log.info("PlaceOrder(" + order + ")")
      val (newOrderBook, orders, trades) = orderBook.placeOrder(order)
      orderBook = newOrderBook

      //context.actorSelection("../webserver") ! orderBook
      trades.foreach((x) => accountantRouter ! TradeNotify(x))
      orders.foreach((x) => accountantRouter ! OrderUpdate(x))

    case CancelOrder(id) => {
      log.info("CancelOrder(" + id + ")")
      orderBook.getOrderById(id) match {
        case Some(order) =>
          accountantRouter ! OrderUpdate(order.copy(quantity = 0))
          orderBook = orderBook.cancelOrder(id)
          sender ! true
        case _ => sender ! false
      }
    }
  }
}

object Test extends App {
  val system = ActorSystem("sputnik")
  val engine = system.actorOf(Props(new Engine(Contract("BTC"))), name = "engine")
  val accountantRouter = system.actorOf(Props(new AccountantRouter), name = "accountant")
  engine ! PlaceOrder(Order(1, 100, 100, DateTime.now, BUY, "testA"))
  engine ! PlaceOrder(Order(2, 100, 50, DateTime.now, BUY, "testB"))
  engine ! PlaceOrder(Order(3, 50, 75, DateTime.now, SELL, "testC"))
  //system.shutdown()
}
