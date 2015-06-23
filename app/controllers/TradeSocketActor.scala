package controllers

import actors.{TradeClassifier, SputnikEventBus}
import akka.actor._
import akka.event._
import models._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object TradeSocketActor {
  def props(out: ActorRef, account: Option[String], contract: Option[String]) = Props(new TradeSocketActor(out, account, contract))

}

class TradeSocketActor(out: ActorRef, accountName: Option[String], contractTicker: Option[String]) extends Actor with ActorLogging {
  override def preStart() = {
    val cFut = contractTicker match {
      case Some(ticker) => Contract.getContract(ticker).map{ Some(_) }
      case None => Future { None }
    }
    val aFut = accountName match {
      case Some(name) => Account.getAccount(name).map{ Some(_) }
      case None => Future { None }
    }
    val agg = for {
      a <- aFut
      c <- cFut
    } yield (a, c)

    agg.foreach(self ! _)
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
