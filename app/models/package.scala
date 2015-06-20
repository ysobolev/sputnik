import java.util.UUID

import play.api.libs.json.Json.JsValueWrapper
import reactivemongo.bson._
import com.github.nscala_time.time.Imports._

import play.api.libs.json._
import play.api.libs.functional.syntax._

package object models {
  type Quantity = Long
  type Price = Long
  type Positions = Map[Contract, Quantity]

  // https://gist.github.com/mikesname/5237809
  object EnumUtils {
    def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
      def reads(json: JsValue): JsResult[E#Value] = json match {
        case JsString(s) => {
          try {
            JsSuccess(enum.withName(s))
          } catch {
            case _: NoSuchElementException => JsError(s"Enumeration expected of type: '${enum.getClass}', but it does not appear to contain the value: '$s'")
          }
        }
        case _ => JsError("String value expected")
      }
    }

    implicit def enumWrites[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
      def writes(v: E#Value): JsValue = JsString(v.toString)
    }

    implicit def enumFormat[E <: Enumeration](enum: E): Format[E#Value] = {
      Format(EnumUtils.enumReads(enum), EnumUtils.enumWrites)
    }
  }

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

  implicit val contractTypeFormat= EnumUtils.enumFormat(ContractType)

  trait Nameable {
    def name: String
  }

  trait SputnikEvent

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

  implicit object TradeWriter extends BSONDocumentWriter[Trade] {
    def write(trade: Trade): BSONDocument = BSONDocument(
      "contract" -> trade.contract,
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
      doc.getAs[Contract]("contract").get,
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

  // http://stackoverflow.com/questions/27285760/play-json-formatter-for-mapint
  implicit val mapReads: Reads[Map[Price, Quantity]] = new Reads[Map[Price, Quantity]] {
    def reads(jv: JsValue): JsResult[Map[Price, Quantity]] =
      JsSuccess(jv.as[Map[Price, Quantity]].map{case (k, v) =>
        k.toLong -> v.asInstanceOf[Long]
      })
  }

  implicit val mapWrites: Writes[Map[Price, Quantity]] = new Writes[Map[Price, Quantity]] {
    def writes(map: Map[Price, Quantity]): JsValue =
      Json.obj(map.map{case (p, q) =>
        val ret: (String, JsValueWrapper) = p.toString -> JsNumber(q)
        ret
      }.toSeq:_*)
  }

  implicit val mapFormat = Format[Map[Price, Quantity]](mapReads, mapWrites)

  implicit val contractFormat = Json.format[Contract]
  implicit val aggregatedOrderBookFormat = Json.format[AggregatedOrderBook]

}
