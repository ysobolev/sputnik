package models

import com.github.nscala_time.time.Imports._
import reactivemongo.bson.BSONObjectID

case class Journal(typ: String,
                   postings: List[Posting],
                   timestamp: DateTime = DateTime.now,
                   _id: BSONObjectID = BSONObjectID.generate) extends SputnikEvent {

  def audit: Boolean = postings.groupBy(_.contract).forall {
    case (c: Contract, l: List[Posting]) =>
      val byAccountType = l.groupBy(_.account.side)
      byAccountType.getOrElse(LedgerSide.ASSET, List[Posting]()).map(_.signedQuantity).sum == byAccountType.getOrElse(LedgerSide.LIABILITY, List[Posting]()).map(_.signedQuantity).sum
  }
}
