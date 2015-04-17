package sputnik

import akka.actor.{Actor, ActorRef}
import com.github.nscala_time.time.Imports._
import sputnik.sputnik.LedgerDirection._
import sputnik.sputnik.LedgerSide._
import sputnik.sputnik._

import scala.collection.mutable

class LedgerException(x: String) extends Exception(x)

abstract class LedgerMessage

case class NewPosting(posting: Posting, count: Int, uuid: String) extends LedgerMessage

case class GetBalance(user: User) extends LedgerMessage

case class Posting(contract: Contract, user: User, quantity: Quantity, direction: LedgerDirection) {
  val timestamp = DateTime.now

  lazy val sign = {
    val user_sign = user match {
      case User(_, ASSET) => 1
      case User(_, LIABILITY) => -1
    }
    val dir_sign = direction match {
      case DEBIT => 1
      case CREDIT => -1
    }
    user_sign * dir_sign
  }
  lazy val signedQuantity = sign * quantity
}

class PostingGroup(uid: String, count: Int, postings: List[Posting], senders: List[ActorRef]) {
  def add(posting: Posting, sender: ActorRef): PostingGroup = {
    new PostingGroup(uid, count, posting :: postings, sender :: senders)
  }

  def ready: Boolean = postings.size == count

  def toJournal(typ: String): Journal = {
    if (ready) {
      val journal = new Journal(typ, postings)
      if (!journal.audit)
        throw new LedgerException("Journal fails audit")
      journal
  }
}

case class Journal(typ: String, postings: List[Posting]) {
  val timestamp = DateTime.now

  def audit: Boolean = postings.foldLeft(0: Quantity)((x, y) => x + y.sign * y.quantity) == 0

}

class Ledger extends Actor {
  var ledger = List[Journal]
  val pending = mutable.Map[String, PostingGroup]()

  def getBalance(user: User, contract: Contract, timestamp: DateTime = DateTime.now): Quantity = {
    for {
      posting@Posting(contract, user, quantity, _) <- ledger
      if posting.timestamp <= timestamp
    } yield posting.signedQuantity
  }

  def receive = {
    case NewPosting(posting, count) =>
    case GetBalance(user, contract) => sender ! getBalance(user, contract)
  }
}


