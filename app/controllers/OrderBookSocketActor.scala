package controllers

import actors.{OrderBookClassifier, SputnikEventBus, MongoFactory}
import akka.actor.Status.Failure
import akka.actor._
import akka.event._
import models.{OrderBook, Contract}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import scala.concurrent.ExecutionContext.Implicits.global

object OrderBookSocketActor {
  def props(out: ActorRef, ticker: String) = Props(new OrderBookSocketActor(out, ticker))

}

class OrderBookSocketActor(out: ActorRef, ticker: String) extends Actor with ActorLogging {
  val contractsColl = MongoFactory.database[BSONCollection]("contracts")
  override def preStart() = {
    val contractFuture = contractsColl.find(BSONDocument("ticker" -> ticker)).cursor[Contract].collect[List]()
    contractFuture.onSuccess {
      case l: List[Contract] if l.size == 1 =>
        self ! l(0)
    }
    contractFuture.onFailure {
      case msg =>
        out ! Failure(msg)
    }
  }
  def subscribe(contract: Contract): Receive = {
    SputnikEventBus.subscribe(self, OrderBookClassifier(Some(contract)))

    LoggingReceive {
      case book: OrderBook =>
        out ! book.aggregate
    }

  }
  val receive: Receive = LoggingReceive {
    case c: Contract => context.become(subscribe(c))
  }

}
