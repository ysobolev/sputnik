package actors.accountant

import java.util.UUID
import actors.Ledger
import akka.actor.{ActorLogging, Actor, Props}
import models.Posting

object Poster {
  def props(count: Int, posting: Posting, uuid: UUID) = Props(new Poster(count, posting, uuid))

  case class PostingResult(uuid: UUID, result: Boolean)
  case class Posted(posting: Posting)

}

class Poster(count: Int, posting: Posting, uuid: UUID) extends Actor with ActorLogging {
  val ledger = context.system.actorSelection("/user/ledger")
  ledger ! Ledger.NewPosting(count, posting, uuid)

  def receive: Receive = {
    case Poster.PostingResult(u, true) =>
      require(u == uuid)
      context.parent ! Poster.Posted(posting)
  }
}

