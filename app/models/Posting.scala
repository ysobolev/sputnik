package models

import com.github.nscala_time.time.Imports._
import play.api.libs.json.Json
import reactivemongo.bson.{BSONDocumentReader, BSONDocument, BSONDocumentWriter}

object Posting {
  implicit val postingFormat = Json.format[Posting]

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
}
case class Posting(contract: Contract,
                   account: Account,
                   quantity: Quantity,
                   direction: LedgerDirection.LedgerDirection,
                   timestamp: DateTime = DateTime.now) extends SputnikEvent[Posting] with FeedMsg {
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

  def toFeed: Posting = this
}

