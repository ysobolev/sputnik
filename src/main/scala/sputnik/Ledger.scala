package sputnik

import akka.actor.{Props, Actor, ActorRef}
import com.github.nscala_time.time.Imports._
import sputnik.LedgerDirection._
import sputnik.LedgerSide._
import sputnik._

import scala.collection.mutable

class LedgerException(x: String) extends Exception(x)

abstract class LedgerMessage

case class NewPosting(count: Int, posting: Posting, uuid: String) extends LedgerMessage
case class AddPosting(count: Int, posting: Posting, sender: ActorRef) extends LedgerMessage
case class PostingResult(result: Boolean) extends LedgerMessage
case class GetBalance(user: User) extends LedgerMessage
case class NewJournal(journal: Journal) extends LedgerMessage

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

class PostingGroup(count: Int, var postings: List[Posting], var senders: List[ActorRef]) extends Actor {
  def add(count: Int, posting: Posting, sender: ActorRef): Unit = {
    if (count != this.count)
      throw new LedgerException("count mismatch")
    else {
      postings = posting :: postings
      senders = sender :: senders
    }
  }

  def ready: Boolean = postings.size == count

  def toJournal(typ: String): Journal = {
    if (ready) {
      val journal = new Journal(typ, postings)
      if (!journal.audit)
        throw new LedgerException("Journal fails audit")
      journal
    }
    else
      throw new LedgerException("Postings not ready")
  }

  def receive = {
    case AddPosting(c, p, s) =>
      add(c, p, s)
      if (ready)
        context.parent ! NewJournal(toJournal(""))
        senders.foreach(_ ! PostingResult(result = true))
  }
}

case class Journal(typ: String, postings: List[Posting]) {
  val timestamp = DateTime.now

  def audit: Boolean = postings.groupBy(_.contract).forall(_._2.map(_.signedQuantity).sum == 0)
}

class Ledger extends Actor {
  var ledger = List[Journal]()
  val pending = mutable.Map[String, ActorRef]()

  def getBalance(user: User, contract: Contract, timestamp: DateTime = DateTime.now): Quantity = {
    val qtys = for {
        entries<- ledger
        posting@Posting(contract, user, quantity, _) <- entries
      if posting.timestamp <= timestamp
    } yield posting.signedQuantity
    qtys.sum
  }

  def receive = {
    case NewPosting(count, posting, uuid) =>
      if (pending contains uuid)
        pending(uuid) ! AddPosting(count, posting, sender())
      else
        pending(uuid) = context.actorOf(Props(new PostingGroup(count, List(posting), List(sender()))), name = uuid)
    case GetBalance(user, contract) => sender ! getBalance(user, contract)
    case NewJournal(journal) =>
      ledger = journal :: ledger
  }
}


