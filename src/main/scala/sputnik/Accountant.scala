package sputnik

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import sputnik.LedgerDirection._
import sputnik.TradeSide._
import sputnik.BookSide._
import scala.concurrent.ExecutionContext.Implicits.global


class AccountantException(x: String) extends Exception(x)

object Accountant
{
  case class PlaceOrder(order: Order)
  case class TradeNotify(trade: Trade, side: TradeSide = MAKER)
  case class OrderUpdate(order: Order)
  case class PostingResult(uuid: UUID, result: Boolean)
  case class PositionsMsg(positions: Positions)
  case class GetPositions(account: Account)

  def props(name: Account): Props = Props(new Accountant(name))
}


class Accountant(name: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val ledger = context.system.actorSelection("/user/ledger")

  type PostingMap = Map[UUID, List[Posting]]
  type OrderMap = Map[Int, Order]
  type TradeMap = Map[UUID, List[(Trade, Order)]]

  ledger ! Ledger.GetPositions(name)

  def receive: Receive = initializing

  val initializing: Receive = LoggingReceive {
    case Accountant.PositionsMsg(positions) =>
      unstashAll()
      context.become(trading(positions, Map.empty, Map.empty, Map.empty))
    case msg =>
      stash()
  }

  def trading(positions: Positions, pendingPostings: PostingMap, pendingTrades: TradeMap, orderMap: OrderMap): Receive = {
    def checkMargin(order: Order) = true

    def post(pendingPostings: PostingMap, posting: Ledger.NewPosting): PostingMap = {
      ledger ! posting
      pendingPostings + ((posting.uuid, posting.posting :: pendingPostings.getOrElse(posting.uuid, List[Posting]())))
    }


    LoggingReceive {
      case Accountant.TradeNotify(t, side) =>
        val myOrder = if (side == MAKER) t.passiveOrder else t.aggressiveOrder
        val spent = myOrder.contract.getCashSpent(t.price, t.quantity)
        val denominatedDirection = if (myOrder.side == BUY) DEBIT else CREDIT
        val payoutDirection = if (myOrder.side == BUY) CREDIT else DEBIT
        val userDenominatedPosting = Posting(myOrder.contract.denominated.get, name, t.quantity, denominatedDirection)
        val userPayoutPosting = Posting(myOrder.contract.payout.get, name, spent, payoutDirection)
        val uuid: UUID = t.uuid

        val newPendingPostings = List(Ledger.NewPosting(4, userDenominatedPosting, uuid), Ledger.NewPosting(4, userPayoutPosting, uuid)).foldLeft(pendingPostings)(post)
        val newPendingTrades = pendingTrades + ((uuid, (t, myOrder) :: pendingTrades.getOrElse(uuid, List[(Trade, Order)]())))
        context.become(trading(positions, newPendingPostings, newPendingTrades, orderMap))

      case Accountant.PostingResult(uuid, true) =>
        val pendingForUuid = pendingPostings.getOrElse(uuid, List())
        val positionChanges = pendingForUuid.map(pending => (pending.contract, pending.signedQuantity))

        def modifyPositions(positions: Positions, x: (Contract, Quantity)) = {
          val (contract, change) = x
          positions + ((contract, positions.getOrElse(contract, 0) + change))
        }

        val newPositions = positionChanges.foldLeft(positions)(modifyPositions)
        val newPendingPostings = pendingPostings - uuid

        def updateOrderMap(orderMap: OrderMap, x: (Trade, Order)): OrderMap = {
          val (trade, order) = x
          val oldOrder = orderMap(order.id)

          if (oldOrder.quantity > trade.quantity) {
            val newOrder = oldOrder.copy(quantity = oldOrder.quantity - trade.quantity)
            orderMap + ((order.id, newOrder))
          }
          else
            orderMap - order.id
        }
        val newOrderMap = pendingTrades.getOrElse(uuid, List()).foldLeft(orderMap)(updateOrderMap)
        val newPendingTrades = pendingTrades - uuid

        context.become(trading(newPositions, newPendingPostings, newPendingTrades, newOrderMap))

      case Accountant.PlaceOrder(o) =>
        if (checkMargin(o)) {
          engineRouter ! Engine.PlaceOrder(o)
          context.become(trading(positions, pendingPostings, pendingTrades, orderMap + ((o.id, o))))
        }
      case Accountant.GetPositions(account) =>
        require(account == name)
        sender ! Accountant.PositionsMsg(positions)
    }
  }
}
