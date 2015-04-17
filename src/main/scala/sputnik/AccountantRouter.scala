package sputnik

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.collection.mutable

abstract class AccountantRouterMsg
case class TradeNotify(trade: Trade) extends AccountantRouterMsg
case class OrderUpdate(order: Order) extends AccountantRouterMsg

class AccountantRouter extends Actor with ActorLogging {
  private val map = mutable.Map[User, ActorRef]()

  def accountantMap(user: User): ActorRef = {
    map.get(user) match {
      case Some(actor: ActorRef) => actor
      case None =>
        map += user -> context.actorOf(Props(new Accountant(user)), name = user.username)
        map(user)
    }
  }

  def receive = {
    case TradeNotify(trade) => {
      log.info("TradeNotify(" + trade + ")")
      trade match {
        case trade @ Trade(aggressive, passive, _, _) =>
          accountantMap(aggressive.user) ! trade
          accountantMap(passive.user) ! trade
      }
    }

    case OrderUpdate(order @ Order(_, _, _, _, _, username)) =>
      log.info("OrderUpdate(" + order + ")")
      accountantMap(username) ! order
  }

}
