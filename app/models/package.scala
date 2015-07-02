import java.util.UUID
import reactivemongo.bson._
import com.github.nscala_time.time.Imports._
import play.api.libs.json._

// These are req'd do not optimize away
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global

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
    val MAKER, TAKER, NULL = Value
  }

  object ContractType extends Enumeration {
    type ContractType = Value
    val CASH, CASH_PAIR, FUTURES, PREDICTION = Value
  }

  implicit val contractTypeFormat= EnumUtils.enumFormat(ContractType)
  implicit val bookSideFormat = EnumUtils.enumFormat(BookSide)
  implicit val ledgerSideFormat = EnumUtils.enumFormat(LedgerSide)

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

}
