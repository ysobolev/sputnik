package controllers

import actors.{OrderBookClassifier, SputnikEventBus}
import akka.actor._
import akka.event._
import models._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global

object OrderBookSocketActor {
  def props(out: ActorRef, ticker: String) = Props(new OrderBookSocketActor(out, ticker))

}

class OrderBookSocketActor(out: ActorRef, ticker: String) extends Actor with ActorLogging {
  override def preStart() = {
    val futContract = Contract.getContract(ticker)
    futContract.foreach(self ! _)
  }
  def subscribe(contract: Contract): Receive = {
    SputnikEventBus.subscribe(self, OrderBookClassifier(Some(contract)))

    LoggingReceive {
      case book: OrderBook =>
        out ! Json.toJson(book.aggregate)
    }
  }
  val receive: Receive = LoggingReceive {
    case c: Contract => context.become(subscribe(c))
  }

}
