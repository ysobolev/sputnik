/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
  type PostingMap = Map[UUID, List[Posting]]
  type OrderMap = Map[Int, Order]
  type TradeMap = Map[UUID, List[(Trade, Order)]]
  type SafePriceMap = Map[Contract, Price]

  case class PlaceOrder(order: Order)
  case class TradeNotify(trade: Trade, side: TradeSide = MAKER)
  case class OrderUpdate(order: Order)
  case class PostingResult(uuid: UUID, result: Boolean)
  case class PositionsMsg(positions: Positions)
  case class GetPositions(account: Account)
  case class UpdateSafePrice(contract: Contract, safePrice: Price)
  case class UpdateSafePriceMap(safePrices: SafePriceMap)

  def props(name: Account): Props = Props(new Accountant(name))
}


class Accountant(name: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val ledger = context.system.actorSelection("/user/ledger")


  ledger ! Ledger.GetPositions(name)

  def receive: Receive = initializing

  val initializing: Receive = LoggingReceive {
    case Accountant.PositionsMsg(positions) =>
      unstashAll()
      context.become(trading(positions, Map.empty, Map.empty, Map.empty, Map.empty))
    case msg =>
      stash()
  }

  def trading(positions: Positions,
              pendingPostings: Accountant.PostingMap,
              pendingTrades: Accountant.TradeMap,
              orderMap: Accountant.OrderMap,
              safePrices: Accountant.SafePriceMap): Receive = {
    def calculateMargin(order: Order, positionOverrides: Positions = Map(), cashOverrides: Positions = Map()) = {
      val usePositions = (positions ++ positionOverrides).withDefault(x => 0)
      val useOrders = (orderMap + ((order.id, order))).values
      val cashPositions = (usePositions.filter(x => x._1.contractType == ContractType.CASH) ++ cashOverrides).withDefault(x => 0)

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
      val maxCashSpentFn = maxCashSpent.foldLeft(Map[Contract, Quantity]().withDefault(x => 0))_
      val maxCashSpentMap = maxCashSpentFn {
        case (map: Map[Contract, Quantity], tuple: (Option[Contract], Quantity)) =>
          map + ((tuple._1.get, map(tuple._1.get) + tuple._2))
      }
      (marginsForContracts._1, marginsForContracts._2, maxCashSpentMap)

    }

    def checkMargin(order: Order) = {
      val (lowMargin, highMargin, maxCashSpent) = calculateMargin(order)
      if (!positions.forall { case (c: Contract, q: Quantity) => q > maxCashSpent.withDefault(x => 0)(c) })
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
        context.become(trading(positions, newPendingPostings, newPendingTrades, orderMap, safePrices))

      case Accountant.PostingResult(uuid, true) =>
        val pendingForUuid = pendingPostings.getOrElse(uuid, List())
        val positionChanges = pendingForUuid.map(pending => (pending.contract, pending.signedQuantity))

        def modifyPositions(positions: Positions, x: (Contract, Quantity)) = {
          val (contract, change) = x
          positions + ((contract, positions.getOrElse(contract, 0) + change))
        }

        val newPositions = positionChanges.foldLeft(positions)(modifyPositions)
        val newPendingPostings = pendingPostings - uuid

        def updateOrderMap(orderMap: Accountant.OrderMap, x: (Trade, Order)): Accountant.OrderMap = {
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

        context.become(trading(newPositions, newPendingPostings, newPendingTrades, newOrderMap, safePrices))

      case Accountant.PlaceOrder(o) =>
        if (checkMargin(o)) {
          engineRouter ! Engine.PlaceOrder(o)
          context.become(trading(positions, pendingPostings, pendingTrades, orderMap + ((o.id, o)), safePrices))
        }
        else
          sender() ! Status.Failure(new AccountantException("insufficient_margin"))

      case Accountant.GetPositions(account) =>
        require(account == name)
        sender ! Accountant.PositionsMsg(positions)
      case Accountant.UpdateSafePrice(contract, price) =>
        context.become(trading(positions, pendingPostings, pendingTrades, orderMap, safePrices + (contract -> price)))
      case Accountant.UpdateSafePriceMap(safePriceMap) =>
        context.become(trading(positions, pendingPostings, pendingTrades, orderMap, safePriceMap))
    }
  }
}
