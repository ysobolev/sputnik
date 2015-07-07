package models

import play.api.libs.json.Json
import reactivemongo.bson.{BSONDocumentReader, BSONDocument, BSONDocumentWriter, BSONObjectID}
import scala.concurrent._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global

// This is req'd, don't optimize away
import play.modules.reactivemongo.json.BSONFormats._


class OrderException(x: String) extends Exception(x)

object IncomingOrder {
  implicit val incomingOrderFormat = Json.format[IncomingOrder]
}

case class IncomingOrder(quantity: Quantity,
                     price: Price,
                     side: BookSide.BookSide,
                     account: String,
                     contract: String) {
  def toOrder: Future[Order] = {
    val future = for {
      c <- Contract.getContract(contract)
      a <- Account.getAccount(account, LedgerSide.LIABILITY)
    } yield (c, a)
    future.map(x => Order(quantity, price, DateTime.now, side, x._2, x._1))
  }
}

object Order {
  implicit val orderFormat = Json.format[Order]

  implicit object OrderWriter extends BSONDocumentWriter[Order] {
    def write(order: Order): BSONDocument = BSONDocument(
      "quantity" -> order.quantity,
      "price" -> order.price,
      "timestamp" -> order.timestamp,
      "side" -> order.side.toString,
      "account" -> order.account,
      "contract" -> order.contract,
      "_id" -> order._id,
      "accepted" -> order.accepted,
      "booked" -> order.booked,
      "cancelled" -> order.cancelled
    )
  }

  implicit object OrderReader extends BSONDocumentReader[Order] {
    def read(doc: BSONDocument): Order = Order(
      doc.getAs[Quantity]("quantity").get,
      doc.getAs[Price]("price").get,
      doc.getAs[DateTime]("timestamp").get,
      BookSide withName doc.getAs[String]("side").get,
      doc.getAs[Account]("account").get,
      doc.getAs[Contract]("contract").get,
      doc.getAs[BSONObjectID]("_id").get,
      doc.getAs[Boolean]("accepted").getOrElse(false),
      doc.getAs[Boolean]("booked").getOrElse(false),
      doc.getAs[Boolean]("cancelled").getOrElse(false)
    )
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
                 cancelled: Boolean = false) extends Ordered[Order] with SputnikEvent[Order] with FeedMsg {
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

  def toFeed: Order = this
}

