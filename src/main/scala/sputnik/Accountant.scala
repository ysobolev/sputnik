/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package sputnik

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import com.mongodb.casbah.commons.MongoDBObject
import org.bson.types.ObjectId
import sputnik.LedgerDirection._
import sputnik.TradeSide._
import sputnik.BookSide._
import scala.concurrent.ExecutionContext.Implicits.global


class AccountantException(x: String) extends Exception(x)

object Accountant
{
  type PostingMap = Map[UUID, List[Posting]]
  type OrderMap = Map[ObjectId, (Order, ActorRef)]
  type OrderActorMap = Map[ActorRef, ObjectId]
  type TradeMap = Map[UUID, List[(Trade, Order)]]
  type SafePriceMap = Map[Contract, Price]
  abstract class AccountantMessage

  case class PlaceOrder(order: Order) extends AccountantMessage
  case class PlaceOrderPersisted(order: Order) extends AccountantMessage
  case class UpdateOrderPersisted(order: Order) extends AccountantMessage
  case class CancelOrder(id: ObjectId) extends AccountantMessage
  case class OrderCancelled(order: Order) extends AccountantMessage
  case class TradeNotify(trade: Trade, side: TradeSide = MAKER) extends AccountantMessage
  case class PostingResult(uuid: UUID, result: Boolean) extends AccountantMessage
  case class PositionsMsg(positions: Positions) extends AccountantMessage
  case class GetPositions(account: Account) extends AccountantMessage
  case class UpdateSafePrice(contract: Contract, safePrice: Price) extends AccountantMessage
  case class UpdateSafePriceMap(safePrices: SafePriceMap) extends AccountantMessage

  def props(name: Account): Props = Props(new Accountant(name))
}

object PersistOrder {
  def props(order: Order): Props = Props(new PersistOrder(order))
  case class UpdateOrder(quantity: Quantity)
  case object CancelOrder
}

class PersistOrder(order: Order) extends Actor with ActorLogging with Stash {
  val ordersColl = MongoFactory.database("orders")
  val query = MongoDBObject("_id" -> order._id)

  val receive = LoggingReceive {
    case _ =>
      stash()
  }
  override def preStart() = {
    val dbObj = order.toMongo

    ordersColl.insert(dbObj)
    context.parent ! Accountant.PlaceOrderPersisted(order)
    unstashAll()
    context.become(tracking(order))
  }

  def tracking(order: Order): Receive = {
    if(order.quantity == 0)
      context.stop(self)

    LoggingReceive {
      case PersistOrder.UpdateOrder(tradeQuantity) =>
        val newOrder = order.copy(quantity = order.quantity - tradeQuantity)
        ordersColl.update(query, newOrder.toMongo)

        context.parent ! Accountant.UpdateOrderPersisted(newOrder)
        context.become(tracking(newOrder))

      case PersistOrder.CancelOrder =>
        val newOrder = order.copy(quantity = 0)
        ordersColl.update(query, newOrder.toMongo)
        context.parent ! Accountant.UpdateOrderPersisted(newOrder)
        context.become(tracking(newOrder))
    }
  }
}

class Accountant(name: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val ledger = context.system.actorSelection("/user/ledger")

  override def preStart() = ledger ! Ledger.GetPositions(name)

  def receive: Receive = initializing

  val initializing: Receive = LoggingReceive {
    case Accountant.PositionsMsg(positions) =>
      unstashAll()
      context.become(trading(State(positions, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)))
    case msg =>
      stash()
  }

  case class State(positions: Positions,
                    pendingPostings: Accountant.PostingMap,
                    pendingTrades: Accountant.TradeMap,
                    orderMap: Accountant.OrderMap,
                    orderActorMap: Accountant.OrderActorMap,
                    safePrices: Accountant.SafePriceMap)

  def trading(state: State): Receive = state match {
    case State(positions, pendingPostings, pendingTrades, orderMap, orderActorMap, safePrices) =>
      def calculateMargin(order: Order, positionOverrides: Positions = Map(), cashOverrides: Positions = Map()) = {
        val usePositions = (positions ++ positionOverrides).withDefault(x => 0)
        val useOrders = order :: orderMap.values.map(_._1).toList
        val cashPositions = (usePositions.filter(x => x._1.contractType == ContractType.CASH) ++ cashOverrides).withDefaultValue(0)

        def marginForContract(contract: Contract): (Quantity, Quantity) = {
          val maxPosition = usePositions(contract) +
            useOrders.filter(order => order.side == BUY && order.contract == contract).map(_.quantity).sum

          val minPosition = usePositions(contract) -
            useOrders.filter(order => order.side == SELL && order.contract == contract).map(_.quantity).sum

          val maxSpent =
            useOrders.filter(order => order.side == BUY && order.contract == contract).map(x => contract.getCashSpent(x.price, x.quantity)).sum

          val maxReceived =
            useOrders.filter(order => order.side == SELL && order.contract == contract).map(x => contract.getCashSpent(x.price, x.quantity)).sum

          contract.contractType match {
            case ContractType.FUTURES => throw new NotImplementedError("Futures not yet implemented. Need safe & reference prices")
            case ContractType.PREDICTION =>
              val payOff = contract.lotSize
              val worstShortCover = if (minPosition < 0) -minPosition * payOff else 0
              val bestShortCover = if (maxPosition < 0) -maxPosition * payOff else 0
              (maxSpent + bestShortCover, -maxReceived + worstShortCover)
            case _ =>
              (0, 0)
          }
        }
        val marginsForContracts = usePositions.keys.map(marginForContract).foldLeft((0, 0))((x, y) => (x._1 + y._1, x._2 + y._2))

        val cashPairOrders = useOrders.filter(x => x.contract.contractType == ContractType.CASH_PAIR)
        val maxCashSpent = cashPairOrders.map(x =>
          if (x.side == BUY)
            (x.contract.denominated, x.contract.getCashSpent(x.price, x.quantity))
          else
            (x.contract.payout, x.quantity))
        val maxCashSpentFn = maxCashSpent.foldLeft(Map[Contract, Quantity]().withDefaultValue(0))_
        val maxCashSpentMap = maxCashSpentFn {
          case (map: Map[Contract, Quantity], tuple: (Option[Contract], Quantity)) =>
            map + ((tuple._1.get, map(tuple._1.get) + tuple._2))
        }
        (marginsForContracts._1, marginsForContracts._2, maxCashSpentMap)

      }

      def checkMargin(order: Order) = {
        val (lowMargin, highMargin, maxCashSpent) = calculateMargin(order)
        if (!positions.forall { case (c: Contract, q: Quantity) => q > maxCashSpent.withDefaultValue(0)(c) })
          false
        else {
          val btcPosition = positions.find { case (c: Contract, _) => c.ticker == "BTC" } match {
            case Some((_, q: Quantity)) => q
            case None => 0
          }
          btcPosition >= highMargin
        }
      }

      def post(pendingPostings: Accountant.PostingMap, posting: Ledger.NewPosting): Accountant.PostingMap = {
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
          context.become(trading(state.copy(pendingPostings = newPendingPostings, pendingTrades = newPendingTrades)))

        case Accountant.PostingResult(uuid, true) =>
          val pendingForUuid = pendingPostings.getOrElse(uuid, List())
          val positionChanges = pendingForUuid.map(pending => (pending.contract, pending.signedQuantity))

          def modifyPositions(positions: Positions, x: (Contract, Quantity)) = {
            val (contract, change) = x
            positions + ((contract, positions.getOrElse(contract, 0) + change))
          }

          val newPositions = positionChanges.foldLeft(positions)(modifyPositions)
          val newPendingPostings = pendingPostings - uuid

          def persistOrderUpdates(x: (Trade, Order)): Unit = {
            val (trade, order) = x
            val (oldOrder, persistRef) = orderMap(order._id)

            persistRef ! PersistOrder.UpdateOrder(trade.quantity)
          }

          val newOrderMap = pendingTrades.getOrElse(uuid, List()).foreach(persistOrderUpdates)
          val newPendingTrades = pendingTrades - uuid

          context.become(trading(state.copy(positions = newPositions, pendingPostings = newPendingPostings, pendingTrades = newPendingTrades)))

        case Accountant.OrderCancelled(o) =>
          val (order, persistRef) = orderMap(o._id)
          persistRef ! PersistOrder.CancelOrder

        case Accountant.CancelOrder(id) =>
          val (order, persistRef) = orderMap(id)
          engineRouter ! Engine.CancelOrder(order.contract, id)

        case Accountant.UpdateOrderPersisted(o) =>
          val newOrderMap = orderMap + (o._id -> (o, sender()))
          context.become(trading(state.copy(orderMap = newOrderMap)))

        case Accountant.PlaceOrder(o) =>
          val persistRef = context.actorOf(PersistOrder.props(o))
          context.watch(persistRef)

        case Terminated(ref) =>
          val orderId = orderActorMap(ref)
          val newOrderMap = orderMap - orderId
          val newOrderActorMap = orderActorMap - ref
          context.unwatch(ref)
          context.become(trading(state.copy(orderMap = newOrderMap, orderActorMap = newOrderActorMap)))

        case Accountant.PlaceOrderPersisted(o) =>
          if (checkMargin(o)) {
            engineRouter ! Engine.PlaceOrder(o)
            context.become(trading(state.copy(orderMap = orderMap + (o._id -> (o, sender())), orderActorMap = orderActorMap + (sender() -> o._id))))
          }
          else
            sender() ! Status.Failure(new AccountantException("insufficient_margin"))

        case Accountant.GetPositions(account) =>
          require(account == name)
          sender ! Accountant.PositionsMsg(positions)

        case Accountant.UpdateSafePrice(contract, price) =>
          context.become(trading(state.copy(safePrices = safePrices + (contract -> price))))

        case Accountant.UpdateSafePriceMap(safePriceMap) =>
          context.become(trading(state.copy(safePrices = safePriceMap)))
      }
  }
}
