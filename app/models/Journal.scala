package models

import com.github.nscala_time.time.Imports._
import reactivemongo.bson.{BSONDocumentReader, BSONDocument, BSONDocumentWriter, BSONObjectID}

object Journal {
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
}

case class Journal(typ: String,
                   postings: List[Posting],
                   timestamp: DateTime = DateTime.now,
                   _id: BSONObjectID = BSONObjectID.generate) {

  def audit: Boolean = postings.groupBy(_.contract).forall {
    case (c: Contract, l: List[Posting]) =>
      val byAccountType = l.groupBy(_.account.side)
      byAccountType.getOrElse(LedgerSide.ASSET, List[Posting]()).map(_.signedQuantity).sum == byAccountType.getOrElse(LedgerSide.LIABILITY, List[Posting]()).map(_.signedQuantity).sum
  }
}
