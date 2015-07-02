/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package actors.accountant

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import models.BookSide._
import models.LedgerDirection._
import models.TradeSide._
import models._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson._
import actors._
import scala.concurrent.ExecutionContext.Implicits.global

class AccountantException(x: String) extends Exception(x)

/** The Accountant ensures that the account is only able to place orders and withdrawals that it
  * has the available margin to handle
  *
  */
object Accountant
{
  type OrderMap = Map[BSONObjectID, (Order, ActorRef)]
  type OrderMapClean = Map[BSONObjectID, Order]
  type SafePriceMap = Map[Contract, Price]

  /** Attempt to place an order. Creates an OrderManager with the order and the sender.
    *
    * @param order
    */
  case class PlaceOrder(order: Order)

  /** Attempt to cancel an order. Contacts the relevant OrderManager and sends it an
    * OrderManager.CancelOrder
    *
    * @param account
    * @param id
    */
  case class CancelOrder(account: Account, id: BSONObjectID)

  /** Notification that an order has been cancelled. Removes order from orderMap
    *
    * @param order
    */
  case class OrderCancelled(order: Order)

  /** Notification that a trade has taken place. Sends an OrderManager.TradeNotify to the OrderManager
    * relevant to this trade
    *
    * @param trade
    * @param side
    */
  case class TradeNotify(trade: Trade, side: TradeSide = NULL)

  /** Get the positions for this account. Returns the positions to the sender.
    *
    * @param account
    */
  case class GetPositions(account: Account)

  /** Get the orders for this account. Returns the cleaned orderMap (OrderMapClean)
    *
    * @param account
    */
  case class GetOrders(account: Account)

  /** Get an order by ID. If it is not in the ordermap, check the database
    *
    * @param account
    * @param id
    */
  case class GetOrder(account: Account, id: BSONObjectID)

  case class UpdateSafePrice(contract: Contract, safePrice: Price)
  case class UpdateSafePriceMap(safePrices: SafePriceMap)

  /** Check to see if an order can be placed. If it can be placed, returns
    * OrderManager.ValidOrder, if not, returns OrderManager.InvalidOrder
    *
    * @param order
    */
  case class ValidateOrder(order: Order)

  /** Possible invalid order types
    *
    */
  trait InvalidOrderTypes
  case object InsufficientMargin extends InvalidOrderTypes
  case object BadPriceQuantity extends InvalidOrderTypes

  /** Deposits cash into the account. Creates a Poster for this account's
    * posting, and sends a NewPosting to the accountantRouter for the other
    * half of this posting
    *
    * @param account
    * @param contract
    * @param quantity
    */
  case class DepositCash(account: Account, contract: Contract, quantity: Quantity)

  /** Receives a posting and creates a Poster for it
    *
    * @param count
    * @param posting
    * @param uuid
    */
  case class NewPosting(count: Int, posting: Posting, uuid: UUID)

  /** Receives notification that a trade has been posted, including the originating
    * order and the relevant postings that have been posted for that trade
    *
    * @param trade
    * @param postings
    * @param order
    */
  case class TradePosted(trade: Trade, postings: Set[Posting], order: Order)

  def props(name: Account): Props = Props(new Accountant(name))
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
      context.become(trading(State(positions, Map.empty, Map.empty)))
    case _ =>
      stash()
  }

  case class State(positions: Positions,
                    orderMap: Accountant.OrderMap,
                    safePrices: Accountant.SafePriceMap)

  def trading(state: State): Receive = state match {
    case State(positions, orderMap, safePrices) =>
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


        case Poster.Posted(posting) =>
          val positionChange = (posting.contract, posting.signedQuantity)
          val newPositions = modifyPosition(positions, positionChange)
          context.become(trading(state.copy(positions = newPositions)))

        case Accountant.TradePosted(trade, postings, order) =>
          val positionChanges = postings.map(posting => (posting.contract, posting.signedQuantity))
          val newPositions = positionChanges.foldLeft(positions)(modifyPosition)
          val oldOrder = orderMap(order._id)._1
          val newOrderMap = order.quantity match {
            case 0L => orderMap - order._id
            case q if q < oldOrder.quantity => orderMap.updated(order._id, (order, orderMap(order._id)._2))
            case _ => orderMap
          }
          SputnikEventBus.publish(trade)

          if (order.quantity < oldOrder.quantity)
            SputnikEventBus.publish(order)

          context.become(trading(state.copy(positions = newPositions, orderMap = newOrderMap)))

        case Accountant.OrderCancelled(o) =>
          val newOrderMap = orderMap - o._id
          context.become(trading(state.copy(orderMap = newOrderMap)))

        case Accountant.CancelOrder(a, id) =>
          require(a == account)
          val (order, managerRef) = orderMap(id)
          managerRef ! OrderManager.CancelOrder

        case Accountant.TradeNotify(t, s) =>
          val order = t.orderBySide(s)
          val orderManager = orderMap(order._id)._2
          orderManager ! OrderManager.TradeNotify(t, s)

        case Accountant.PlaceOrder(o) =>
          context.actorOf(OrderManager.props(o, sender(), account))

        case Accountant.ValidateOrder(o) =>
          if (!validateOrder(o)) {
            sender() ! OrderManager.InvalidOrder(Accountant.BadPriceQuantity)
          } else if (!checkMargin(o)) {
            sender() ! OrderManager.InvalidOrder(Accountant.InsufficientMargin)
          } else {
            sender() ! OrderManager.ValidOrder
            SputnikEventBus.publish(o)
            context.become(trading(state.copy(orderMap = orderMap + (o._id -> (o, sender())))))
          }

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
