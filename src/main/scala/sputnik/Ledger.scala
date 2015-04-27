package sputnik

import java.util.UUID

import akka.actor.{ActorLogging, Props, Actor, ActorRef}
import com.github.nscala_time.time.Imports._
import sputnik._
import sputnik.LedgerDirection._
import sputnik.LedgerSide._

import scala.collection.mutable

class LedgerException(x: String) extends Exception(x)

abstract class LedgerMessage

case class NewPosting(count: Int, posting: Posting, uuid: UUID) extends LedgerMessage
case class AddPosting(count: Int, posting: Posting) extends LedgerMessage
case class PostingResult(uuid: UUID, result: Boolean) extends LedgerMessage
case class GetBalances(account: Account) extends LedgerMessage
case class NewJournal(uuid: UUID, journal: Journal) extends LedgerMessage

case class Posting(contract: Contract, user: Account, quantity: Quantity, direction: LedgerDirection) {
  val timestamp = DateTime.now

  lazy val sign = {
    val user_sign = user match {
      case Account(_, ASSET) => 1
      case Account(_, LIABILITY) => -1
    }
    val dir_sign = direction match {
      case DEBIT => 1
      case CREDIT => -1
    }
    user_sign * dir_sign
  }
  lazy val signedQuantity = sign * quantity
}

class PostingGroup(uuid: UUID, count: Int, var postings: List[Posting])
  extends Actor with ActorLogging {
  def add(count: Int, posting: Posting): Unit = {
    if (count != this.count)
      throw new LedgerException("count mismatch")
    else {
      postings = posting :: postings
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
    case AddPosting(c, p) =>
      log.info(s"AddPosting($c, $p")
      add(c, p)
      if (ready) {
        context.parent ! NewJournal(uuid, toJournal(""))
        val accountants = postings.map(_.user.name).toSet[String].map(a => context.system.actorSelection("/user/accountant/" + a))
        accountants.foreach(_ ! PostingResult(uuid, result = true))
      }
  }
}

case class Journal(typ: String, postings: List[Posting]) {
  val timestamp = DateTime.now

  def audit: Boolean = postings.groupBy(_.contract).forall(_._2.map(_.signedQuantity).sum == 0)
}

class Ledger extends Actor with ActorLogging {
  var ledger = List[Journal]()
  val pending = mutable.Map[UUID, ActorRef]()

  def getBalances(user: Account, timestamp: DateTime = DateTime.now): Map[Contract, Quantity] = {
    val quantities = for {
      entry <- ledger
      posting@Posting(contract, user, _, _) <- entry.postings
      if entry.timestamp <= timestamp
    } yield (contract, posting.signedQuantity)
    quantities.groupBy[Contract](_._1).mapValues(_.map(_._2).sum)
  }

  def receive = {
    case NewPosting(count, posting, uuid) =>
      val currentSender = sender()
      log.info(s"NewPosting($count, $posting, $uuid")
      if (!pending.contains(uuid))
        pending(uuid) = context.actorOf(Props(new PostingGroup(uuid, count, List())), name = uuid.toString)

      pending(uuid) ! AddPosting(count, posting)

    case GetBalances(user) =>
      log.info(s"GetBalances($user)")
      sender ! getBalances(user)

    case NewJournal(uuid, journal) =>
      log.info(s"NewJournal($uuid, $journal)")
      ledger = journal :: ledger
      pending(uuid)
      context.stop(pending(uuid))
      pending -= uuid
  }
}


