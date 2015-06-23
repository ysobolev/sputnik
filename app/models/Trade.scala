package models

import java.util.UUID

import com.github.nscala_time.time.Imports._
import reactivemongo.bson.{BSONDocumentReader, BSONDocument, BSONDocumentWriter, BSONObjectID}

object Trade {

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
}
case class Trade(contract: Contract,
                 aggressiveOrder: Order,
                 passiveOrder: Order,
                 quantity: Quantity,
                 price: Price,
                 timestamp: DateTime = DateTime.now,
                 uuid: UUID = UUID.randomUUID,
                 _id: BSONObjectID = BSONObjectID.generate,
                 posted: Boolean = false) extends SputnikEvent