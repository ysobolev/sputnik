/**
 * Created by sameer on 4/15/15.
 */

package sputnik

import com.github.nscala_time.time.Imports._
import sputnik.sputnik.BookSide._
import sputnik.sputnik._

import scala.math.Ordered.orderingToOrdered
class OrderException(x: String) extends Exception(x)

case class Order(id: Int, quantity: Quantity, price: Price, timestamp: DateTime, side: BookSide, user: User) extends Ordered[Order] {

  private val sign = if (side == BUY) -1 else 1

  def matches(that: Order): Boolean = (this.side != that.side) && (sign * (this.price - that.price) <= 0)

  def isExhausted: Boolean = quantity == 0

  /** Price-Time ordering */
  def compare(that: Order): Int =
    if (this.side == that.side)
      (sign * this.price, this.timestamp) compare (that.sign * that.price, that.timestamp)
    else
      throw new OrderException("can't compare buy and sell")

}


