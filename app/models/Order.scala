package models

import com.github.nscala_time.time.Imports._
import reactivemongo.bson.BSONObjectID

/**
 * Created by sameer on 6/17/15.
 */
class OrderException(x: String) extends Exception(x)


case class Order(quantity: Quantity,
                 price: Price,
                 timestamp: DateTime,
                 side: BookSide.BookSide,
                 account: Account,
                 contract: Contract,
                 _id: BSONObjectID = BSONObjectID.generate,
                 accepted: Boolean = false,
                 booked: Boolean = false,
                 cancelled: Boolean = false) extends Ordered[Order] with SputnikEvent {
  private val sign = if (side == BookSide.BUY) -1 else 1

  def matches(that: Order): Boolean = (this.side != that.side) && (sign * (this.price - that.price) <= 0)

  def isExhausted: Boolean = quantity == 0L

  /** Price-Time ordering */

  def compare(that: Order): Int =
    if (this.side == that.side) {
      val priceCompare = (this.sign * this.price) compare (that.sign * that.price)
      if (priceCompare == 0)
        this.timestamp compare that.timestamp
      else
        priceCompare
    }
    else
      throw new OrderException("can't compare buy and sell")

}