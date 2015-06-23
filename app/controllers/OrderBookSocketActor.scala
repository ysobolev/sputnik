package controllers

import actors.{OrderBookClassifier, SputnikEventBus, MongoFactory}
import akka.actor.Status.Failure
import akka.actor._
import akka.event._
import models._
import play.api.libs.json._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import scala.concurrent.ExecutionContext.Implicits.global

object OrderBookSocketActor {
  def props(out: ActorRef, ticker: String) = Props(new OrderBookSocketActor(out, ticker))

}

class OrderBookSocketActor(out: ActorRef, ticker: String) extends Actor with ActorLogging {
  override def preStart() = {
    val futContract = getContract(ticker)
    futContract.foreach(self ! _)
  }
  def subscribe(contract: Contract): Receive = {
    SputnikEventBus.subscribe(self, OrderBookClassifier(Some(contract)))

    LoggingReceive {
      case book: OrderBook =>
        val aggJson = Json.toJson(book.aggregate)
        out ! aggJson
    }
  }
  val receive: Receive = LoggingReceive {
    case c: Contract => context.become(subscribe(c))
  }

}
