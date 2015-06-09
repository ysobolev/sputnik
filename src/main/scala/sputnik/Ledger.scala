/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package sputnik

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import com.github.nscala_time.time.Imports._
import sputnik._
import sputnik.LedgerDirection._
import sputnik.LedgerSide._

import scala.collection.mutable

class LedgerException(x: String) extends Exception(x)

object Ledger {
  case class NewPosting(count: Int, posting: Posting, uuid: UUID)

  case class GetPositions(account: Account)

  case class NewJournal(uuid: UUID, journal: Journal)
}

object PostingGroup {
  case class AddPosting(count: Int, posting: Posting)
  case object JournalPersisted

  def props(uuid: UUID, count: Int): Props = Props(new PostingGroup(uuid, count))

}

object PersistJournal {
  def props(journal: Journal): Props = Props(new PersistJournal(journal))
}

class PersistJournal(journal: Journal) extends Actor with ActorLogging {
  val ledgerColl = MongoFactory.database("ledger")

  override def preStart(): Unit = {
    val dbObj = journal.toMongo

    ledgerColl.insert(dbObj)
    context.parent ! PostingGroup.JournalPersisted
    context.stop(self)
  }
  val receive: Receive = LoggingReceive {
    case _ =>
  }
}

class PostingGroup(uuid: UUID, count: Int) extends Actor with ActorLogging with Stash {
  def waitForPersist(journal: Journal, persister: ActorRef): Receive = LoggingReceive {
    case PostingGroup.JournalPersisted =>
      context.parent ! Ledger.NewJournal(uuid, journal)
      val accountants = journal.postings.map(_.account.name).toSet[String].map(a => context.system.actorSelection("/user/accountant/" + a))
      accountants.foreach(_ ! Accountant.PostingResult(uuid, result = true))
      context.stop(self)
    case Terminated(p) if p == persister =>
      context.unwatch(p)
  }

  def state(postings: List[Posting]): Receive = {
    def add(count: Int, posting: Posting): List[Posting] = {
      if (count != this.count)
        throw new LedgerException("count mismatch")
      else {
        posting :: postings
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

    if (ready) {
      val journal = toJournal("")
      val persister = context.actorOf(PersistJournal.props(journal), name = "persist")
      context.watch(persister)
      unstashAll()
      waitForPersist(journal, persister)
    }
    else {
      LoggingReceive {
        case PostingGroup.AddPosting(c, p) =>
          context.become(state(add(c, p)))
        case msg =>
          log.debug(s"Stashing $msg")
          stash()
      }
    }


  }

  val receive = state(List())

}


class Ledger extends Actor with ActorLogging {
  val receive = state(List(), Map())

  def state(ledger: List[Journal], pending: Map[UUID, ActorRef]): Receive = {
    def getBalances(account: Account, timestamp: DateTime = DateTime.now): Positions = {
      val quantities = for {
        entry <- ledger
        posting@Posting(contract, a, _, _, _) <- entry.postings if account == a
        if entry.timestamp <= timestamp
      } yield (contract, posting.signedQuantity)
      quantities.groupBy[Contract](_._1).mapValues(_.map(_._2).sum)
    }

    LoggingReceive {
      case Ledger.NewPosting(count, posting, uuid) =>
        val currentSender = sender()
        val newPending = if (!pending.contains(uuid)) pending + ((uuid, context.actorOf(PostingGroup.props(uuid, count), name = uuid.toString))) else pending
        newPending(uuid) ! PostingGroup.AddPosting(count, posting)
        context.become(state(ledger, newPending))

      case Ledger.GetPositions(user) =>
        sender ! Accountant.PositionsMsg(getBalances(user))

      case Ledger.NewJournal(uuid, journal) =>
        context.become(state(journal :: ledger, pending - uuid))
    }
  }
}


