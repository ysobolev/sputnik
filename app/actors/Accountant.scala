/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package actors

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import models.TradeSide._
import models.BookSide._
import models.LedgerDirection._
import reactivemongo.api.collections.default.BSONCollection
import scala.concurrent.ExecutionContext.Implicits.global
import reactivemongo.bson._
import models._

class AccountantException(x: String) extends Exception(x)

object Accountant
{
  type OrderMap = Map[BSONObjectID, (Order, ActorRef)]
  type OrderMapClean = Map[BSONObjectID, Order]
  type OrderActorMap = Map[ActorRef, BSONObjectID]
  type SafePriceMap = Map[Contract, Price]

  case class PlaceOrder(order: Order)
  case class PlaceOrderPersisted(order: Order, sender: ActorRef)
  case class UpdateOrderPersisted(order: Order, sender: ActorRef)
  case class CancelOrder(account: Account, id: BSONObjectID)
  case class OrderCancelled(order: Order)
  case class OrderPlaced(order: Order)
  case class OrderBooked(order: Order)
  case class TradeNotify(trade: Trade, side: TradeSide = MAKER)
  case class TradePostedPersisted(trade: Trade)
  case class PostingResult(uuid: UUID, result: Boolean)

  case class GetPositions(account: Account)

  case class GetOrders(account: Account)

  case class GetOrder(account: Account, id: BSONObjectID)

  case class UpdateSafePrice(contract: Contract, safePrice: Price)
  case class UpdateSafePriceMap(safePrices: SafePriceMap)
  case object InsufficientMargin
  case object InvalidOrder

  case class DepositCash(account: Account, contract: Contract, quantity: Quantity)
  case class NewPosting(count: Int, posting: Posting, uuid: UUID)

  case class Posted(posting: Posting)
  case class TradePosted(trade: Trade, postings: Set[Posting], side: TradeSide)

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
        self ! TradePoster.TradePersisted
        context.parent ! TradePoster.TradePersisted
        SputnikEventBus.publish(trade)
        unstashAll()
    }
  }

  val tracking: Receive = LoggingReceive {
    case PersistTrade.TradePosted =>
      val newTrade = trade.copy(posted = true)
      tradesColl.update(query, newTrade).map { lastError =>
        self ! Accountant.TradePostedPersisted(newTrade)
        context.parent ! Accountant.TradePostedPersisted(newTrade)
      }
    case Accountant.TradePostedPersisted(_) =>
      context.stop(self)
  }

  val receive = LoggingReceive {
    case TradePoster.TradePersisted =>
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

class PersistOrder(order: Order, requester: ActorRef) extends Actor with ActorLogging with Stash {
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
      self ! Accountant.PlaceOrderPersisted(order, requester)
      context.parent ! Accountant.PlaceOrderPersisted(order, requester)
      unstashAll()
    }
  }

  def tracking(order: Order): Receive = {
    if(order.quantity == 0L)
      context.stop(self)

    def updateAndBecome(newOrder: Order) = {
      ordersColl.update(query, newOrder).map { lastError =>
        context.parent ! Accountant.UpdateOrderPersisted(newOrder, requester)
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

object Poster {
  def props(count: Int, posting: Posting, uuid: UUID) = Props(new Poster(count, posting, uuid))
}

class Poster(count: Int, posting: Posting, uuid: UUID) extends Actor with ActorLogging {
  val ledger = context.system.actorSelection("/user/ledger")
  ledger ! Ledger.NewPosting(count, posting, uuid)

  def receive: Receive = {
    case Accountant.PostingResult(u, true) =>
      require(u == uuid)
      context.parent ! Accountant.Posted(posting)
  }
}

object TradePoster {
  def props(trade: Trade, side: TradeSide, account: Account) = Props(new TradePoster(trade, side, account))
  case object TradePersisted
}

class TradePoster(trade: Trade, side: TradeSide, account: Account) extends Actor with ActorLogging {
  context.actorOf(PersistTrade.props(trade, side))

  def receive: Receive = {
    case TradePoster.TradePersisted =>
      val tradePersister = sender()
      val myOrder = if (side == MAKER) trade.passiveOrder else trade.aggressiveOrder
      val spent = myOrder.contract.getCashSpent(trade.price, trade.quantity)
      val denominatedDirection = if (myOrder.side == BUY) DEBIT else CREDIT
      val payoutDirection = if (myOrder.side == BUY) CREDIT else DEBIT
      val userDenominatedPosting = Posting(myOrder.contract.denominated.get, account, spent, denominatedDirection)
      val userPayoutPosting = Posting(myOrder.contract.payout.get, account, trade.quantity, payoutDirection)
      val uuid: UUID = trade.uuid
      val postingSet = Set(userDenominatedPosting, userPayoutPosting)
      postingSet.foreach(x => context.actorOf(Poster.props(4, x, uuid)))
      context.become(waitForPosted(tradePersister, postingSet, Set.empty))
  }

  def waitForPosted(tradePersister: ActorRef, postingsRemaining: Set[Posting], postingsPosted: Set[Posting]): Receive = {
    if (postingsRemaining.isEmpty) {
      tradePersister ! PersistTrade.TradePosted
      waitForPersisted(postingsPosted)
    }
    else {
      LoggingReceive {
        case msg@Accountant.Posted(posting) =>
          context.become(waitForPosted(tradePersister, postingsRemaining - posting, postingsPosted + posting))
      }
    }
  }

  def waitForPersisted(postings: Set[Posting]): Receive = {
    case Accountant.TradePostedPersisted(t) =>
      context.parent ! Accountant.TradePosted(t, postings, side)
      context.stop(self)
  }
}

class Accountant(account: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val accountantRouter = context.system.actorSelection("/user/accountant")
  val ledger = context.system.actorSelection("/user/ledger")

  override def preStart() = ledger ! Ledger.GetPositions(account)

  def receive: Receive = initializing

  val initializing: Receive = LoggingReceive {
    case positions: Positions =>
      unstashAll()
      context.become(trading(State(positions, Map.empty, Map.empty, Map.empty)))
    case _ =>
      stash()
  }

  case class State(positions: Positions,
                    orderMap: Accountant.OrderMap,
                    orderActorMap: Accountant.OrderActorMap,
                    safePrices: Accountant.SafePriceMap)

  def trading(state: State): Receive = state match {
    case State(positions, orderMap, orderActorMap, safePrices) =>
      def validateOrder(order: Order): Boolean = {
        if (order.price % order.contract.tickSize != 0 || order.price < 0 || order.quantity < 0)
          false
        else
          order.contract.contractType match {
            case ContractType.CASH_PAIR => order.quantity % order.contract.lotSize == 0
            case ContractType.PREDICTION => 0 <= order.price && order.price <= order.contract.denominator
          }
      }

      def calculateMargin(order: Order, positionOverrides: Positions = Map(), cashOverrides: Positions = Map()) = {
        val usePositions = (positions ++ positionOverrides).withDefaultValue(0L)
        val useOrders = order :: orderMap.values.map(_._1).toList.filterNot(_.cancelled)
        val cashPositions = (usePositions.filter(x => x._1.contractType == ContractType.CASH) ++ cashOverrides).withDefaultValue(0L)

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
              (0L, 0L)
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

      def modifyPosition(positions: Positions, x: (Contract, Quantity)) = {
        val (contract, change) = x
        positions + ((contract, positions.getOrElse(contract, 0L) + change))
      }

      LoggingReceive {
        case Accountant.GetOrders(a) =>
          require(a == account)
          sender() ! orderMap.mapValues { case (o: Order, a: ActorRef) => o }

        case Accountant.GetOrder(a, id) =>
          require(a == account)
          val s = sender()
          orderMap.get(id) match {
            case Some((o: Order, _)) =>
              s ! o
            case None =>
              val ordersColl = MongoFactory.database[BSONCollection]("orders")
              val query = BSONDocument("_id" -> id)
              ordersColl.find(query).cursor[Order].collect[List]().map {
                case l: List[Order] if l.size == 1 =>
                  s ! l.head
              }
          }

        case Accountant.TradeNotify(t, side) =>
          context.actorOf(TradePoster.props(t, side, account))

        case Accountant.Posted(posting) =>
          val positionChange = (posting.contract, posting.signedQuantity)
          val newPositions = modifyPosition(positions, positionChange)
          context.become(trading(state.copy(positions = newPositions)))

        case Accountant.TradePosted(trade, postings, side) =>
          val positionChanges = postings.map(posting => (posting.contract, posting.signedQuantity))

          val newPositions = positionChanges.foldLeft(positions)(modifyPosition)

          def persistOrderAndUpdateMap(order: Order): Accountant.OrderMap = {
            val (oldOrder, persistRef) = orderMap(order._id)

            persistRef ! PersistOrder.UpdateOrder(trade.quantity)
            orderMap.updated(order._id, (oldOrder.copy(quantity = order.quantity - trade.quantity), orderMap(order._id)._2))
          }

          val order = if (side == MAKER) trade.passiveOrder else trade.aggressiveOrder
          val newOrderMap = persistOrderAndUpdateMap(order)
          SputnikEventBus.publish(trade)

          context.become(trading(state.copy(positions = newPositions, orderMap = newOrderMap)))

        case Accountant.OrderCancelled(o) =>
          val (order, persistRef) = orderMap(o._id)
          persistRef ! PersistOrder.CancelOrder

        case Accountant.OrderBooked(o) =>
          val (order, persistRef) = orderMap(o._id)
          persistRef ! PersistOrder.BookOrder

        case Accountant.CancelOrder(a, id) =>
          require(a == account)
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

        case Accountant.PlaceOrderPersisted(o, requester) =>
          if (!validateOrder(o)) {
            requester ! Accountant.InvalidOrder
            sender() ! PersistOrder.CancelOrder
          } else if (!checkMargin(o)) {
            requester ! Accountant.InsufficientMargin
            sender() ! PersistOrder.CancelOrder
          } else {
            engineRouter ! Engine.PlaceOrder(o)
            sender() ! PersistOrder.AcceptOrder
            requester ! Accountant.OrderPlaced(o.copy(accepted=true))
          }
          SputnikEventBus.publish(o)
          context.become(trading(state.copy(orderMap = orderMap + (o._id -> (o, sender())), orderActorMap = orderActorMap + (sender() -> o._id))))

        case Accountant.GetPositions(a) =>
          require(a == account)
          sender() ! positions

        case Accountant.UpdateSafePrice(contract, price) =>
          context.become(trading(state.copy(safePrices = safePrices + (contract -> price))))

        case Accountant.UpdateSafePriceMap(safePriceMap) =>
          context.become(trading(state.copy(safePrices = safePriceMap)))

        case Accountant.DepositCash(a, contract, quantity) =>
          require(account == a)
          val myAccountPosting = Posting(contract, account, quantity, CREDIT)
          val remotePosting = Posting(contract, Account("cash", LedgerSide.ASSET), quantity, DEBIT)
          val uuid = UUID.randomUUID
          context.actorOf(Poster.props(2, myAccountPosting, uuid))
          accountantRouter ! Accountant.NewPosting(2, remotePosting, uuid)

        case Accountant.NewPosting(count, posting, uuid) =>
          context.actorOf(Poster.props(count, posting, uuid))

      }
  }
}
