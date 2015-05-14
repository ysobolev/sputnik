package sputnik

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import sputnik.TradeSide._

class AccountantRouter extends Actor with ActorLogging with GetOrCreateChild {
  implicit def childFactory(account: Account): Props = Accountant.props(account)

  def receive = LoggingReceive {
    case Accountant.TradeNotify(trade, _) =>
      trade match {
        case trade @ Trade(aggressive: Order, passive: Order, _, _) =>
          getOrCreateChild(aggressive.account) ! Accountant.TradeNotify(trade, TAKER)
          getOrCreateChild(passive.account) ! Accountant.TradeNotify(trade, MAKER)
      }
    case Accountant.PlaceOrder(order) =>
      getOrCreateChild(order.account) ! Accountant.PlaceOrder(order)
}

}
