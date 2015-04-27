package sputnik

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import sputnik.TradeSide._


import scala.collection.mutable

abstract class AccountantRouterMsg
case class TradeNotify(trade: Trade, side: TradeSide = MAKER) extends AccountantRouterMsg
case class OrderUpdate(order: Order) extends AccountantRouterMsg

class AccountantRouter extends Actor with ActorLogging {
  private val map = mutable.Map[Account, ActorRef]()

  def accountantMap(user: Account): ActorRef = {
    map.get(user) match {
      case Some(actor: ActorRef) => actor
      case None =>
        map += user -> context.actorOf(Props(new Accountant(user)), name = user.name)
        map(user)
    }
  }

  def receive = {
    case TradeNotify(trade, _) =>
      log.info("TradeNotify(" + trade + ")")
      trade match {
        case trade @ Trade(aggressive, passive, _, _) =>
          accountantMap(aggressive.user) ! TradeNotify(trade, TAKER)
          accountantMap(passive.user) ! TradeNotify(trade, MAKER)
      }

    case OrderUpdate(order) =>
      log.info("OrderUpdate(" + order + ")")
      accountantMap(order.user) ! OrderUpdate(order)
  }

}
