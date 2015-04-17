package sputnik

import sputnik.sputnik.BookSide._

import scala.collection.SortedSet

/**
 * Created by sameer on 4/15/15.
 */
class OrderBook(bids: SortedSet[Order], asks: SortedSet[Order]) {
  def this() = {
    this(SortedSet(), SortedSet())
  }

  /** Places an order in the order book. Returns a tuple of the updated orderbook,
    * all affected orders, and all resulting fills, in order
    *
    * @param order The order being placed
    * @return
    */
  def placeOrder(order: Order): (OrderBook, List[Order], List[Trade]) = {
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
      if (order.isExhausted || matchBook.isEmpty)
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
            val fill = Trade(order, best, quantityTraded, priceTraded)
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
    (if (order.side == BUY) new OrderBook(newMyBook, newBook) else new OrderBook(newBook, newMyBook), (newOrder :: orders).reverse, fills.reverse)
  }
  def getOrderById(id: Int): Option[Order] = (bids ++ asks).find(_.id == id)
  def cancelOrder(id: Int): OrderBook = new OrderBook(bids.filter(_.id != id), asks.filter(_.id != id))

  override def toString =
    "OrderBook(Bids(" + bids + "), Asks(" + asks + "))"
}
