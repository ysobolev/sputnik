package controllers

import actors.{SputnikClassifierContractAccount, TradeClassifier, SputnikEventBus}
import akka.actor._
import akka.event._
import models._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object FeedSocketActor {
  def props[FeedType <: FeedMsg](out: ActorRef,
            classifierFactory: (Set[Contract], Set[Account]) => SputnikClassifierContractAccount,
            writer: Writes[FeedType],
            account: Option[String],
            contract: Option[String]) =
    Props(new FeedSocketActor[FeedType](out, classifierFactory, writer, account, contract))


}

class FeedSocketActor[FeedType <: FeedMsg](out: ActorRef,
                      classifierFactory: (Set[Contract], Set[Account]) => SputnikClassifierContractAccount,
                      writer: Writes[FeedType],
                      accountName: Option[String],
                      contractTicker: Option[String])
  extends Actor with ActorLogging {
  override def preStart() = {
    val cFut = contractTicker match {
      case Some(ticker) => Contract.getContract(ticker).mapTo[Contract].map {
        Set(_)
      }
      case None => Future {
        Set.empty
      }
    }
    val aFut = accountName match {
      case Some(name) => Account.getAccount(name).mapTo[Account].map {
        Set(_)
      }
      case None => Future {
        Set.empty
      }
    }
    val agg = for {
      a <- aFut
      c <- cFut
    } yield (c, a)

    agg.map(self ! _)
  }

  def active(seen: Set[SputnikEvent[FeedType]]): Receive = LoggingReceive {
    case event: SputnikEvent[FeedType] =>
      if (!seen.contains(event)) {
        out ! Json.toJson(writer.writes(event.toFeed))
        context.become(active(seen + event))
      }
  }

  val receive: Receive = LoggingReceive {
    case (c: Set[Contract], a: Set[Account]) =>
      SputnikEventBus.subscribe(self, classifierFactory(c, a))
      context.become(active(Set.empty))
  }

}
