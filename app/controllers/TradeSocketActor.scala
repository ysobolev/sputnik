package controllers

import actors.{TradeClassifier, SputnikEventBus}
import akka.actor._
import akka.event._
import models._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global

object TradeSocketActor {
  def props(out: ActorRef, account: Option[String], contract: Option[String]) = Props(new TradeSocketActor(out, account, contract))

}

class TradeSocketActor(out: ActorRef, accountName: Option[String], contractTicker: Option[String]) extends Actor with ActorLogging {
  override def preStart() = {
    val fut = for {
      c <- contractTicker.map(c => Contract.getContract(c))
      a <- accountName.map(a => Account.getAccount(a))
    } yield (a, c) 
    fut.foreach(self ! _)
  }
  def subscribe(account: Option[Account], contract: Option[Contract]): Receive = {
    SputnikEventBus.subscribe(self, TradeClassifier(contract, account.toSet))

    LoggingReceive {
      case trade: Trade =>
        out ! Json.toJson(trade.toFeed)
    }
  }
  val receive: Receive = LoggingReceive {
    case (a: Option[Account], c: Option[Contract]) => context.become(subscribe(a, c))
  }

}
