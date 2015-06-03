/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import java.util.UUID

import com.github.nscala_time.time.Imports._
import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBObject
import org.bson.types.ObjectId

package object sputnik {
  type Quantity = Int
  type Price = Int
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

  trait DBAble {
    def toMongo: DBObject
  }

  object Account {
    def fromMongo(o: MongoDBObject): Account = Account(
      o.as[String]("name") ,
      LedgerSide withName o.as[String]("side")
    )
  }

  case class Account(name: String, side: LedgerSide.LedgerSide = LedgerSide.LIABILITY) extends Nameable with DBAble {
    def toMongo: DBObject = MongoDBObject("name" -> name, "side" -> side.toString)
  }

  object Contract {
    def fromMongo(o: MongoDBObject): Contract = Contract(
      o.as[String]("ticker"),
      o.getAs[MongoDBObject]("denominated") match {
        case Some(ob) => Some(Contract.fromMongo(ob))
        case None => None
      },
      o.getAs[MongoDBObject]("payout") match {
        case Some(ob) => Some(Contract.fromMongo(ob))
        case None => None
      },
      o.as[Int]("tickSize"),
      o.as[Int]("lotSize"),
      o.as[Int]("denominator"),
      ContractType withName o.as[String]("contractType")
    )
  }

  case class Contract(ticker: String, denominated: Option[Contract], payout: Option[Contract], tickSize: Int,
                      lotSize: Int, denominator: Int,
                      contractType: ContractType.ContractType) extends Nameable {
    contractType match {
      case ContractType.CASH =>
      case _ => if (payout.isEmpty || denominated.isEmpty) throw new Exception("Can't create non-CASH without denominated and payout")
    }

    def toMongo: DBObject = MongoDBObject(
      "ticker" -> ticker,
      "denominated" -> denominated.map(_.toMongo),
      "payout" -> payout.map(_.toMongo),
      "tickSize" -> tickSize,
      "lotSize" -> lotSize,
      "denominator" -> denominator,
      "contractType" -> contractType.toString)

    def getCashSpent(price: Price, quantity: Quantity): Quantity = {
      val pBig: BigInt = price
      val qBig: BigInt = quantity
      contractType match {
        case ContractType.CASH_PAIR => (qBig * pBig / (denominator * payout.get.denominator)).toInt
        case _ => (qBig * pBig * lotSize / denominator).toInt
      }
    }

    def priceToWire(price: BigDecimal): Price = {
      val p = contractType match {
        case ContractType.CASH_PAIR => price * denominated.get.denominator * denominator
        case _ => price * denominator
      }
      (p - p % tickSize).toInt
    }

    def quantityToWire(quantity: BigDecimal): Quantity = {
      val p = contractType match {
        case ContractType.CASH => quantity * denominator
        case ContractType.FUTURES => quantity
        case ContractType.PREDICTION => quantity
        case ContractType.CASH_PAIR =>
          val q = quantity * payout.get.denominator
          q - q % lotSize
      }
      p.toInt
    }

    val name = ticker.replace("/", "")
  }

  object Posting {
    def fromMongo(o: MongoDBObject): Posting = Posting(
      Contract.fromMongo(o.as[MongoDBObject]("contract")),
      Account.fromMongo(o.as[MongoDBObject]("account")),
      o.as[Quantity]("quantity"),
      LedgerDirection withName o.as[String]("direction"),
      o.as[DateTime]("timestamp")
    )
  }

  case class Posting(contract: Contract, account: Account, quantity: Quantity, direction: LedgerDirection.LedgerDirection, timestamp: DateTime = DateTime.now) {
    require(contract.contractType != ContractType.CASH_PAIR)

    def toMongo: DBObject = MongoDBObject(
      "contract" -> contract.toMongo,
      "account" -> account.toMongo,
      "quantity" -> quantity,
      "direction" -> direction.toString,
      "timestamp" -> timestamp
    )

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

  object Trade {
    def fromMongo(o: MongoDBObject): Trade = Trade(
      Order.fromMongo(o.as[MongoDBObject]("aggressiveOrder")),
      Order.fromMongo(o.as[MongoDBObject]("passiveOrder")),
      o.as[Quantity]("quantity"),
      o.as[Price]("price"),
      o.as[DateTime]("timestamp")
    )

  }

  case class Trade(aggressiveOrder: Order, passiveOrder: Order, quantity: Quantity, price: Price, timestamp: DateTime = DateTime.now, uuid: UUID = UUID.randomUUID) {

    def toMongo: DBObject = MongoDBObject(
      "aggressiveOrder" -> aggressiveOrder.toMongo,
      "passiveOrder" -> passiveOrder.toMongo,
      "quantity" -> quantity,
      "price" -> price
    )
  }

  class OrderException(x: String) extends Exception(x)

  object Order {
    def fromMongo(o: MongoDBObject) = Order(
      o.as[Quantity]("quantity"),
      o.as[Price]("price"),
      o.as[DateTime]("timestamp"),
      BookSide withName o.as[String]("side"),
      Account.fromMongo(o.as[MongoDBObject]("account")),
      Contract.fromMongo(o.as[MongoDBObject]("contract")),
      o.as[ObjectId]("_id")
    )
  }

  case class Order(quantity: Quantity, price: Price, timestamp: DateTime, side: BookSide.BookSide, account: Account, contract: Contract, _id: ObjectId = new ObjectId()) extends Ordered[Order] {

    private val sign = if (side == BookSide.BUY) -1 else 1

    def matches(that: Order): Boolean = (this.side != that.side) && (sign * (this.price - that.price) <= 0)

    def isExhausted: Boolean = quantity == 0

    def toMongo: DBObject = MongoDBObject(
      "_id" -> _id,
      "quantity" -> quantity,
      "price" -> price,
      "timestamp" -> timestamp,
      "side" -> side.toString,
      "account" -> account.toMongo,
      "contract" -> contract.toMongo
    )

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

  object Journal {
    def fromMongo(o: MongoDBObject) = Journal(
      o.as[String]("typ"),
      o.as[List[MongoDBObject]]("postings").map(Posting.fromMongo),
      o.as[DateTime]("timestamp")
    )
  }

  case class Journal(typ: String, postings: List[Posting], timestamp: DateTime = DateTime.now) {
    def toMongo: DBObject = MongoDBObject(
      "typ" -> typ,
      "postings" -> postings.map(_.toMongo),
      "timestamp" -> timestamp
    )

    def audit: Boolean = postings.groupBy(_.contract).forall(_._2.map(_.signedQuantity).sum == 0)
  }

}
