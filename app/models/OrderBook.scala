/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package models

import play.api.libs.json.Json

import scala.collection.SortedSet
import reactivemongo.bson._
import models.BookSide._

case class PriceQuantity(price: Price, quantity: Quantity)
object AggregatedOrderBook {
  implicit val pqFormat = Json.format[PriceQuantity]
  implicit val aggregatedOrderBookFormat = Json.format[AggregatedOrderBook]
}

case class AggregatedOrderBook(contract: Contract, bids: List[PriceQuantity], asks: List[PriceQuantity])

case class OrderBook(bids: SortedSet[Order], asks: SortedSet[Order], seenOrders: Set[BSONObjectID], contract: Contract) extends SputnikEvent {
  def this(contract: Contract) = {
    this(SortedSet(), SortedSet(), Set(), contract)
  }

  /** Places an order in the order book. Returns a tuple of the updated orderbook,
    * all affected orders, and all resulting fills, in order
    *
    * @param order The order being placed
    * @return
    */
  def placeOrder(order: Order): (OrderBook, List[Order], List[Trade]) = {
    if (seenOrders contains order._id)
      (this, List(), List())
    else {
      val (matchBook, myBook) = if (order.side == BUY) (asks, bids) else (bids, asks)

      /** Recursively matches the incoming order against the book until it can't match no more
        *
        * @param order The incoming order
        * @param orders accumulator of orders touched so far
        * @param fills accumulator of fills produced so far
        * @param book the half of the book we're matching against
        * @return
        */
      def doMatch(order: Order, orders: List[Order], fills: List[Trade], book: SortedSet[Order]): (Order, List[Order], List[Trade], SortedSet[Order]) = {
        if (order.isExhausted || book.isEmpty)
          (order, orders, fills, book)
        else {
          /** Match two orders against each other
            *
            * @param order The incoming order
            * @param best The best passive order in the book
            * @return The fill, if any, and the two orders after matching against each other
            */
          def singleMatch(order: Order, best: Order): (Option[Trade], Order, Order) = {
            if (order matches best) {
              val quantityTraded = math.min(order.quantity, best.quantity)
              val priceTraded = best.price
              val newAggressive = order.copy(quantity = order.quantity - quantityTraded)
              val newPassive = best.copy(quantity = best.quantity - quantityTraded)
              val fill = Trade(contract, order, best, quantityTraded, priceTraded)
              (Some(fill), newAggressive, newPassive)
            }
            else {
              (None, order, best)
            }
          }
          val best: Order = book.head

          val (fillOption, newOrder, newBest) = singleMatch(order, best)

          fillOption match {
            case Some(fill) => doMatch(newOrder, newBest :: orders, fill :: fills, if (!newBest.isExhausted) book.tail + newBest else book.tail)
            case None => (newOrder, orders, fills, book)
          }
        }
      }

      val (newOrder, orders, fills, newBook) = doMatch(order, List[Order](), List[Trade](), matchBook)

      val newMyBook = if (!newOrder.isExhausted) myBook + newOrder else myBook
      val newSeenOrders = seenOrders + order._id
      (if (order.side == BUY) new OrderBook(newMyBook, newBook, newSeenOrders, contract) else new OrderBook(newBook, newMyBook, newSeenOrders, contract), (newOrder :: orders).reverse, fills.reverse)
    }
  }
  def getOrderById(id: BSONObjectID): Option[Order] = (bids ++ asks).find(_._id == id)
  def cancelOrder(id: BSONObjectID): OrderBook = new OrderBook(bids.filter(_._id != id), asks.filter(_._id != id), seenOrders, contract)

  override def toString =
    "OrderBook(Bids(" + bids + "), Asks(" + asks + "))"

  def aggregate: AggregatedOrderBook = {
    val bidsAggregate = bids.groupBy(x => x.price).mapValues(_.toList.map(_.quantity).sum).map { case (p, q) => PriceQuantity(p, q) }.toList
    val asksAggregate = asks.groupBy(x => x.price).mapValues(_.toList.map(_.quantity).sum).map { case (p, q) => PriceQuantity(p, q) }.toList
    AggregatedOrderBook(contract, bidsAggregate, asksAggregate)
  }
}
