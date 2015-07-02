package actors.accountant

import java.util.UUID
import actors.Ledger
import akka.actor.{PoisonPill, ActorLogging, Actor, Props}
import models.Posting

object Poster {
  def props(count: Int, posting: Posting, uuid: UUID) = Props(new Poster(count, posting, uuid))

  /** Receive this message when the ledger has finished posting. Send the Posted message and shutdown.
    *
    * @param uuid
    * @param result
    */
  case class PostingResult(uuid: UUID, result: Boolean)

  /** Send this message to show that the posting has been posted
    *
    * @param posting
    */
  case class Posted(posting: Posting)

}

class Poster(count: Int, posting: Posting, uuid: UUID) extends Actor with ActorLogging {
  val ledger = context.system.actorSelection("/user/ledger")
  ledger ! Ledger.NewPosting(count, posting, uuid)

  def receive: Receive = {
    case Poster.PostingResult(u, true) =>
      require(u == uuid)
      context.parent ! Poster.Posted(posting)
      self ! PoisonPill
  }
}

