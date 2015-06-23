package actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import models._
import models.TradeSide._

object AccountantRouter {
  def props: Props = Props(new AccountantRouter)
}

class AccountantRouter extends Actor with ActorLogging with GetOrCreateChild {
  implicit def childFactory(account: Account): Props = Accountant.props(account)

  def receive = LoggingReceive {
    case Accountant.TradeNotify(trade, _) =>
      trade match {
        case trade: Trade =>
          getOrCreateChild(trade.aggressiveOrder.account) ! Accountant.TradeNotify(trade, TAKER)
          getOrCreateChild(trade.passiveOrder.account) ! Accountant.TradeNotify(trade, MAKER)
      }
    case Accountant.PlaceOrder(order) =>
      getOrCreateChild(order.account).tell(Accountant.PlaceOrder(order), sender())
    case Accountant.GetPositions(account) =>
      getOrCreateChild(account).tell(Accountant.GetPositions(account), sender())
    case Accountant.NewPosting(count, posting, uuid) =>
      getOrCreateChild(posting.account).tell(Accountant.NewPosting(count, posting, uuid), sender())
    case Accountant.DepositCash(account, contract, quantity) =>
      getOrCreateChild(account).tell(Accountant.DepositCash(account, contract, quantity), sender())
  }
}
