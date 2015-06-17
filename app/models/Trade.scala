package models

import java.util.UUID

import com.github.nscala_time.time.Imports._
import reactivemongo.bson.BSONObjectID


case class Trade(contract: Contract,
                 aggressiveOrder: Order,
                 passiveOrder: Order,
                 quantity: Quantity,
                 price: Price,
                 timestamp: DateTime = DateTime.now,
                 uuid: UUID = UUID.randomUUID,
                 _id: BSONObjectID = BSONObjectID.generate,
                 posted: Boolean = false) extends SputnikEvent