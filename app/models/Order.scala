package models

import com.github.nscala_time.time.Imports._
import reactivemongo.bson.BSONObjectID
import scala.concurrent._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global

class OrderException(x: String) extends Exception(x)

case class IncomingOrder(quantity: Quantity,
                     price: Price,
                     side: BookSide.BookSide,
                     account: String,
                     contract: String) {
  def toOrder: Future[Order] = {
    val future = for {
      c <- getContract(contract)
      a <- getAccount(account, LedgerSide.LIABILITY)
    } yield (c, a)
    future.map(x => Order(quantity, price, DateTime.now, side, x._2, x._1))
  }
}

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