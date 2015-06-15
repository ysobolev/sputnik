/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package sputnik

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import reactivemongo.api.collections.default.BSONCollection
import sputnik.LedgerDirection._
import sputnik.TradeSide._
import sputnik.BookSide._
import scala.concurrent.ExecutionContext.Implicits.global
import reactivemongo.bson._

class AccountantException(x: String) extends Exception(x)

object Accountant
{
  type PostingMap = Map[UUID, List[Posting]]
  type OrderMap = Map[BSONObjectID, (Order, ActorRef)]
  type OrderActorMap = Map[ActorRef, BSONObjectID]
  type TradeMap = Map[UUID, List[(Trade, Order, ActorRef)]]
  type SafePriceMap = Map[Contract, Price]

  case class PlaceOrder(order: Order)
  case class PlaceOrderPersisted(order: Order, sender: ActorRef)
  case class UpdateOrderPersisted(order: Order, sender: ActorRef)
  case class CancelOrder(id: BSONObjectID)
  case class OrderCancelled(order: Order)
  case class OrderBooked(order: Order)
  case class TradeNotify(trade: Trade, side: TradeSide = MAKER)
  case class TradePersisted(trade: Trade, side: TradeSide)
  case class TradePostedPersisted(uuid: UUID)
  case class PostingResult(uuid: UUID, result: Boolean)
  case class PositionsMsg(positions: Positions)
  case class GetPositions(account: Account)
  case class UpdateSafePrice(contract: Contract, safePrice: Price)
  case class UpdateSafePriceMap(safePrices: SafePriceMap)

  case class DepositCash(account: Account, contract: Contract, quantity: Quantity)
  case class NewPosting(count: Int, posting: Posting, uuid: UUID)

  def props(name: Account): Props = Props(new Accountant(name))
}

object PersistTrade {
  def props(trade: Trade, side: TradeSide): Props = Props(new PersistTrade(trade, side))
  case class TradePosted(uuid: UUID)
}

class PersistTrade(trade: Trade, side: TradeSide) extends Actor with ActorLogging with Stash {
  val tradesColl = MongoFactory.database[BSONCollection](if (side == MAKER) "tradesMaker" else "tradesTaker")
  val query = BSONDocument("_id" -> trade._id)

  override def preStart(): Unit = {
    tradesColl.insert(trade).map { lastError =>
        self ! Accountant.TradePersisted(trade, side)
        context.parent ! Accountant.TradePersisted(trade, side)
        unstashAll()
    }
  }

  val tracking: Receive = LoggingReceive {
    case PersistTrade.TradePosted =>
      val newTrade = trade.copy(posted = true)
      tradesColl.update(query, newTrade).map { lastError =>
        self ! Accountant.TradePostedPersisted
        context.parent ! Accountant.TradePostedPersisted
      }
    case Accountant.TradePostedPersisted =>
      context.stop(self)
  }

  val receive = LoggingReceive {
    case Accountant.TradePersisted =>
      context.become(tracking)

    case msg =>
      log.debug(s"Stashing $msg")
      stash()
  }
}
object PersistOrder {
  def props(order: Order, sender: ActorRef): Props = Props(new PersistOrder(order, sender))
  case class UpdateOrder(quantity: Quantity)
  case object CancelOrder
  case object AcceptOrder
  case object BookOrder
}

class PersistOrder(order: Order, s: ActorRef) extends Actor with ActorLogging with Stash {
  val ordersColl = MongoFactory.database[BSONCollection]("orders")
  val query = BSONDocument("_id" -> order._id)

  val receive = LoggingReceive {
    case Accountant.PlaceOrderPersisted(o, _) =>
      context.become(tracking(o))
    case msg =>
      log.debug(s"Stashing $msg")
      stash()
  }

  override def preStart() = {
    ordersColl.insert(order).map { lastError =>
      self ! Accountant.PlaceOrderPersisted(order, s)
      context.parent ! Accountant.PlaceOrderPersisted(order, s)
      unstashAll()
    }
  }

  def tracking(order: Order): Receive = {
    if(order.quantity == 0L)
      context.stop(self)

    def updateAndBecome(newOrder: Order) = {
      ordersColl.update(query, newOrder).map { lastError =>
        context.parent ! Accountant.UpdateOrderPersisted(newOrder, s)
      }
      context.become(tracking(newOrder))
    }

    LoggingReceive {
      case PersistOrder.UpdateOrder(tradeQuantity) =>
        updateAndBecome(order.copy(quantity = order.quantity - tradeQuantity))

      case PersistOrder.CancelOrder =>
        updateAndBecome(order.copy(quantity = 0L, cancelled = true))

      case PersistOrder.AcceptOrder =>
        updateAndBecome(order.copy(accepted = true))

      case PersistOrder.BookOrder =>
        updateAndBecome(order.copy(booked = true))

    }
  }
}

class Accountant(account: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val accountantRouter = context.system.actorSelection("/user/accountant")
  val ledger = context.system.actorSelection("/user/ledger")

  override def preStart() = ledger ! Ledger.GetPositions(account)

  def receive: Receive = initializing

  val initializing: Receive = LoggingReceive {
    case Accountant.PositionsMsg(positions) =>
      unstashAll()
      context.become(trading(State(positions, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)))
    case _ =>
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
        val usePositions = (positions ++ positionOverrides).withDefault(x => 0L)
        val useOrders = order :: orderMap.values.map(_._1).toList.filterNot(_.cancelled)
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
              val worstShortCover = if (minPosition < 0L) -minPosition * payOff else 0L
              val bestShortCover = if (maxPosition < 0L) -maxPosition * payOff else 0L
              (maxSpent + bestShortCover, -maxReceived + worstShortCover)
            case _ =>
              (0, 0)
          }
        }
        val marginsForContracts = usePositions.keys.map(marginForContract).foldLeft((0L, 0L))((x, y) => (x._1 + y._1, x._2 + y._2))

        val cashPairOrders = useOrders.filter(x => x.contract.contractType == ContractType.CASH_PAIR)
        val maxCashSpent = cashPairOrders.map(x =>
          if (x.side == BUY)
            (x.contract.denominated, x.contract.getCashSpent(x.price, x.quantity))
          else
            (x.contract.payout, x.quantity))
        val maxCashSpentFn = maxCashSpent.foldLeft(Map[Contract, Quantity]().withDefaultValue(0L))_
        val maxCashSpentMap = maxCashSpentFn {
          case (map: Map[Contract, Quantity], tuple: (Option[Contract], Quantity)) =>
            map + ((tuple._1.get, map(tuple._1.get) + tuple._2))
        }
        (marginsForContracts._1, marginsForContracts._2, maxCashSpentMap)

      }

      def checkMargin(order: Order) = {
        val (lowMargin, highMargin, maxCashSpent) = calculateMargin(order)
        if (!maxCashSpent.forall { case (c: Contract, q: Quantity) => q <= positions.withDefaultValue(0L)(c) })
          false
        else {
          val btcPosition = positions.find { case (c: Contract, _) => c.ticker == "BTC" } match {
            case Some((_, q: Quantity)) => q
            case None => 0L
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
          context.actorOf(PersistTrade.props(t, side))

        case Accountant.TradePersisted(t, side) =>
          val persister = sender()
          val myOrder = if (side == MAKER) t.passiveOrder else t.aggressiveOrder
          val spent = myOrder.contract.getCashSpent(t.price, t.quantity)
          val denominatedDirection = if (myOrder.side == BUY) DEBIT else CREDIT
          val payoutDirection = if (myOrder.side == BUY) CREDIT else DEBIT
          val userDenominatedPosting = Posting(myOrder.contract.denominated.get, account, t.quantity, denominatedDirection)
          val userPayoutPosting = Posting(myOrder.contract.payout.get, account, spent, payoutDirection)
          val uuid: UUID = t.uuid

          val newPendingPostings = List(Ledger.NewPosting(4, userDenominatedPosting, uuid), Ledger.NewPosting(4, userPayoutPosting, uuid)).foldLeft(pendingPostings)(post)
          val newPendingTrades = pendingTrades + ((uuid, (t, myOrder, persister) :: pendingTrades.getOrElse(uuid, List[(Trade, Order, ActorRef)]())))
          context.become(trading(state.copy(pendingPostings = newPendingPostings, pendingTrades = newPendingTrades)))

        case Accountant.PostingResult(uuid, true) =>
          val pendingForUuid = pendingPostings.getOrElse(uuid, List())
          val positionChanges = pendingForUuid.map(pending => (pending.contract, pending.signedQuantity))

          def modifyPositions(positions: Positions, x: (Contract, Quantity)) = {
            val (contract, change) = x
            positions + ((contract, positions.getOrElse(contract, 0L) + change))
          }

          val newPositions = positionChanges.foldLeft(positions)(modifyPositions)
          val newPendingPostings = pendingPostings - uuid

          def persistOrderUpdates(x: (Trade, Order, ActorRef)): Unit = {
            val (trade, order, persister) = x
            val (oldOrder, persistRef) = orderMap(order._id)

            persistRef ! PersistOrder.UpdateOrder(trade.quantity)
          }

          val newOrderMap = pendingTrades.getOrElse(uuid, List()).foreach(persistOrderUpdates)
          pendingTrades.get(uuid) match {
            case Some(l: List[(Trade, Order, ActorRef)]) =>
              l.foreach {
                case (_, _, persister: ActorRef) => persister ! PersistTrade.TradePosted
              }
            case None =>
          }
          val newPendingTrades = pendingTrades - uuid

          context.become(trading(state.copy(positions = newPositions, pendingPostings = newPendingPostings, pendingTrades = newPendingTrades)))

        case Accountant.TradePostedPersisted =>

        case Accountant.OrderCancelled(o) =>
          val (order, persistRef) = orderMap(o._id)
          persistRef ! PersistOrder.CancelOrder

        case Accountant.OrderBooked(o) =>
          val (order, persistRef) = orderMap(o._id)
          persistRef ! PersistOrder.BookOrder

        case Accountant.CancelOrder(id) =>
          val (order, persistRef) = orderMap(id)
          engineRouter ! Engine.CancelOrder(order.contract, id)

        case Accountant.UpdateOrderPersisted(o, s) =>
          val newOrderMap = orderMap + (o._id -> (o, sender()))
          context.become(trading(state.copy(orderMap = newOrderMap)))

        case Accountant.PlaceOrder(o) =>
          val persistRef = context.actorOf(PersistOrder.props(o, sender()))
          context.watch(persistRef)

        case Terminated(ref) =>
          val orderId = orderActorMap(ref)
          val newOrderMap = orderMap - orderId
          val newOrderActorMap = orderActorMap - ref
          context.unwatch(ref)
          context.become(trading(state.copy(orderMap = newOrderMap, orderActorMap = newOrderActorMap)))

        case Accountant.PlaceOrderPersisted(o, s) =>
          if (checkMargin(o)) {
            engineRouter ! Engine.PlaceOrder(o)
            sender() ! PersistOrder.AcceptOrder
          }
          else {
            s ! Status.Failure(new AccountantException("insufficient_margin"))
            sender() ! PersistOrder.CancelOrder
          }
          context.become(trading(state.copy(orderMap = orderMap + (o._id -> (o, sender())), orderActorMap = orderActorMap + (sender() -> o._id))))

        case Accountant.GetPositions(a) =>
          require(a == account)
          sender ! Accountant.PositionsMsg(positions)

        case Accountant.UpdateSafePrice(contract, price) =>
          context.become(trading(state.copy(safePrices = safePrices + (contract -> price))))

        case Accountant.UpdateSafePriceMap(safePriceMap) =>
          context.become(trading(state.copy(safePrices = safePriceMap)))

        case Accountant.DepositCash(a, contract, quantity) =>
          require(account == a)
          val myAccountPosting = Posting(contract, account, quantity, CREDIT)
          val remotePosting = Posting(contract, Account("cash", LedgerSide.ASSET), quantity, DEBIT)
          val uuid = UUID.randomUUID
          val newPendingPostings = post(pendingPostings, Ledger.NewPosting(2, myAccountPosting, uuid))
          accountantRouter ! Accountant.NewPosting(2, remotePosting, uuid)
          context.become(trading(state.copy(pendingPostings = newPendingPostings)))

        case Accountant.NewPosting(count, posting, uuid) =>
          val newPendingPostings = post(pendingPostings, Ledger.NewPosting(count, posting, uuid))
          context.become(trading(state.copy(pendingPostings = newPendingPostings)))

      }
  }
}
