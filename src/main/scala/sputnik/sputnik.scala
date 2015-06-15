/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import java.util.UUID

import com.github.nscala_time.time.Imports._
import reactivemongo.bson._

package object sputnik {
  implicit object DatetimeReader extends BSONReader[BSONDateTime, DateTime]{
    def read(bson: BSONDateTime): DateTime = new DateTime(bson.value)
  }

  implicit object DatetimeWriter extends BSONWriter[DateTime, BSONDateTime]{
    def write(t: DateTime): BSONDateTime = BSONDateTime(t.getMillis)
  }

  implicit object UUIDReader extends BSONReader[BSONString, UUID]{
    def read(bson: BSONString): UUID = UUID.fromString(bson.value)
  }

  implicit object UUIDWriter extends BSONWriter[UUID, BSONString]{
    def write(u: UUID): BSONString = BSONString(u.toString)
  }

  type Quantity = Long
  type Price = Long
  type Positions = Map[Contract, Quantity]

  object BookSide extends Enumeration {
    type BookSide = Value
    val BUY, SELL = Value
  }

  object LedgerSide extends Enumeration {
    type LedgerSide = Value
    val ASSET, LIABILITY = Value
  }

  object LedgerDirection extends Enumeration {
    type LedgerDirection = Value
    val DEBIT, CREDIT = Value
  }

  object TradeSide extends Enumeration {
    type TradeSide = Value
    val MAKER, TAKER = Value
  }

  object ContractType extends Enumeration {
    type ContractType = Value
    val CASH, CASH_PAIR, FUTURES, PREDICTION = Value
  }

  trait Nameable {
    def name: String
  }

  implicit object AccountWriter extends BSONDocumentWriter[Account] {
    def write(account: Account): BSONDocument = BSONDocument(
      "name" -> account.name,
      "side" -> account.side.toString
    )
  }
  implicit object AccountReader extends BSONDocumentReader[Account] {
    def read(doc: BSONDocument): Account = {
      Account(
        doc.getAs[String]("name").get,
        LedgerSide withName doc.getAs[String]("side").get
      )
    }
  }

  case class Account(name: String, side: LedgerSide.LedgerSide = LedgerSide.LIABILITY) extends Nameable

  implicit object ContractWriter extends BSONDocumentWriter[Contract] {
    def write(contract: Contract): BSONDocument = BSONDocument(
      "ticker" -> contract.ticker,
      "denominated" -> contract.denominated.map(write),
      "payout" -> contract.payout.map(write),
      "tickSize" -> contract.tickSize,
      "lotSize" -> contract.lotSize,
      "denominator" -> contract.denominator,
      "contractType" -> contract.contractType.toString
    )
  }

  implicit object ContractReader extends BSONDocumentReader[Contract] {
    def read(doc: BSONDocument): Contract = Contract(
      doc.getAs[String]("ticker").get,
      doc.getAs[Contract]("denominated"),
      doc.getAs[Contract]("payout"),
      doc.getAs[Long]("tickSize").get,
      doc.getAs[Long]("lotSize").get,
      doc.getAs[Long]("denominator").get,
      ContractType withName doc.getAs[String]("contractType").get
    )
  }

  case class Contract(ticker: String, denominated: Option[Contract], payout: Option[Contract], tickSize: Long,
                      lotSize: Long, denominator: Long,
                      contractType: ContractType.ContractType) extends Nameable {
    contractType match {
      case ContractType.CASH =>
      case _ => if (payout.isEmpty || denominated.isEmpty) throw new Exception("Can't create non-CASH without denominated and payout")
    }

    def getCashSpent(price: Price, quantity: Quantity): Quantity = {
      contractType match {
        case ContractType.CASH_PAIR => quantity * price / (denominator * payout.get.denominator)
        case _ => quantity * price * lotSize / denominator
      }
    }

    def priceToWire(price: BigDecimal): Price = {
      val p = contractType match {
        case ContractType.CASH_PAIR => price * denominated.get.denominator * denominator
        case _ => price * denominator
      }
      (p - p % tickSize).toLongExact
    }

    def quantityToWire(quantity: BigDecimal): Quantity = {
      val q = contractType match {
        case ContractType.CASH => quantity * denominator
        case ContractType.FUTURES => quantity
        case ContractType.PREDICTION => quantity
        case ContractType.CASH_PAIR =>
          val q = quantity * payout.get.denominator
          q - q % lotSize
      }
      q.toLongExact
    }

    val name = ticker.replace("/", "")
  }

  implicit object PostingWriter extends BSONDocumentWriter[Posting] {
    def write(posting: Posting): BSONDocument = BSONDocument(
      "contract" -> posting.contract,
      "account" -> posting.account,
      "quantity" -> posting.quantity,
      "direction" -> posting.direction.toString,
      "timestamp" -> posting.timestamp
    )
  }

  implicit object PostingReader extends BSONDocumentReader[Posting] {
    def read(doc: BSONDocument): Posting = Posting(
      doc.getAs[Contract]("contract").get,
      doc.getAs[Account]("account").get,
      doc.getAs[Quantity]("quantity").get,
      LedgerDirection withName doc.getAs[String]("direction").get,
      doc.getAs[DateTime]("timestamp").get
    )
  }

  case class Posting(contract: Contract, account: Account, quantity: Quantity, direction: LedgerDirection.LedgerDirection, timestamp: DateTime = DateTime.now) {
    require(contract.contractType != ContractType.CASH_PAIR)

    lazy val sign = {
      val user_sign = account match {
        case Account(_, LedgerSide.ASSET) => 1
        case Account(_, LedgerSide.LIABILITY) => -1
      }
      val dir_sign = direction match {
        case LedgerDirection.DEBIT => 1
        case LedgerDirection.CREDIT => -1
      }
      user_sign * dir_sign
    }
    lazy val signedQuantity = sign * quantity
  }

  implicit object TradeWriter extends BSONDocumentWriter[Trade] {
    def write(trade: Trade): BSONDocument = BSONDocument(
      "aggressiveOrder" -> trade.aggressiveOrder,
      "passiveOrder" -> trade.passiveOrder,
      "quantity" -> trade.quantity,
      "price" -> trade.price,
      "timestamp" -> trade.timestamp,
      "uuid" -> trade.uuid,
      "_id" -> trade._id,
      "posted" -> trade.posted
    )
  }

  implicit object TradeReader extends BSONDocumentReader[Trade] {
    def read(doc: BSONDocument): Trade = Trade(
      doc.getAs[Order]("aggressiveOrder").get,
      doc.getAs[Order]("passiveOrder").get,
      doc.getAs[Quantity]("quantity").get,
      doc.getAs[Price]("price").get,
      doc.getAs[DateTime]("timestamp").get,
      doc.getAs[UUID]("uuid").get,
      doc.getAs[BSONObjectID]("_id").get,
      doc.getAs[Boolean]("posted").get
    )
  }


  case class Trade(aggressiveOrder: Order, passiveOrder: Order, quantity: Quantity, price: Price, timestamp: DateTime = DateTime.now, uuid: UUID = UUID.randomUUID, _id: BSONObjectID = BSONObjectID.generate, posted: Boolean = false)

  class OrderException(x: String) extends Exception(x)

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

  case class Order(quantity: Quantity,
                   price: Price,
                   timestamp: DateTime,
                   side: BookSide.BookSide,
                   account: Account,
                   contract: Contract,
                   _id: BSONObjectID = BSONObjectID.generate,
                   accepted: Boolean = false,
                   booked: Boolean = false,
                   cancelled: Boolean = false) extends Ordered[Order] {
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

  implicit object JournalWriter extends BSONDocumentWriter[Journal] {
    def write(journal: Journal): BSONDocument = BSONDocument(
      "typ" -> journal.typ,
      "postings" -> journal.postings
    )
  }
  implicit object JournalReader extends BSONDocumentReader[Journal] {
    def read(doc: BSONDocument): Journal = Journal(
      doc.getAs[String]("typ").get,
      doc.getAs[List[Posting]]("postings").toList.flatten,
      doc.getAs[DateTime]("timestamp").get,
      doc.getAs[BSONObjectID]("_id").get
    )
  }

  case class Journal(typ: String, postings: List[Posting], timestamp: DateTime = DateTime.now, _id: BSONObjectID = BSONObjectID.generate) {

    def audit: Boolean = postings.groupBy(_.contract).forall {
      case (c: Contract, l: List[Posting]) =>
        val byAccountType = l.groupBy(_.account.side)
        byAccountType.getOrElse(LedgerSide.ASSET, List[Posting]()).map(_.signedQuantity).sum == byAccountType.getOrElse(LedgerSide.LIABILITY, List[Posting]()).map(_.signedQuantity).sum
    }
  }

}
