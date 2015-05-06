package sputnik

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import sputnik.TradeSide._


import scala.collection.mutable

abstract class AccountantRouterMsg
case class TradeNotify(trade: Trade, side: TradeSide = MAKER) extends AccountantRouterMsg
case class OrderUpdate(order: Order) extends AccountantRouterMsg

class AccountantRouter extends Actor with ActorLogging with GetOrCreateChild {
  def childFactory(c: Contract) = new Engine(c)
  def getOrCreateChild = super.getOrCreateChild[Account, Accountant]((a) => new Accountant(a))_

  def receive = {
    case TradeNotify(trade, _) =>
      log.info("TradeNotify(" + trade + ")")
      trade match {
        case trade @ Trade(aggressive, passive, _, _) =>
          this.getOrCreateChild(aggressive.user) ! TradeNotify(trade, TAKER)
          this.getOrCreateChild(passive.user) ! TradeNotify(trade, MAKER)
      }

    case OrderUpdate(order) =>
      log.info("OrderUpdate(" + order + ")")
      this.getOrCreateChild(order.user) ! OrderUpdate(order)
  }

}
