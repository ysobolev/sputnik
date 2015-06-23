package models

import actors.MongoFactory
import play.api.libs.json.Json
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{BSONDocumentReader, BSONDocumentWriter, BSONDocument}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object Account {
  implicit val accountFormat = Json.format[Account]

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

  implicit def getAccount(name: String, side: LedgerSide.LedgerSide = LedgerSide.LIABILITY): Future[Account] = {
    val accountsColl = MongoFactory.database[BSONCollection]("accounts")
    val accountsFuture = accountsColl.find(BSONDocument("name" -> name, "side" -> side.toString)).cursor[Account].collect[List]()
    accountsFuture.map {
      case l: List[Account] if l.size == 1 =>
        l.head
      case _ =>
        val a = Account(name, side)
        accountsColl.insert(a)
        a
    }
  }

}

case class Account(name: String, side: LedgerSide.LedgerSide = LedgerSide.LIABILITY) extends Nameable