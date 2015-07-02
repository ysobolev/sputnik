package actors.accountant

import java.util.UUID

import actors._
import akka.actor._
import akka.event.LoggingReceive
import models.LedgerDirection._
import models.{Posting, Account, Trade}
import models.TradeSide._
import models.BookSide._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import scala.concurrent.ExecutionContext.Implicits.global

/** The TradeManager handles the lifecycle of a trade.
  *
  * * TradeNotify from the engine
  * * Postings get sent to the ledger
  * * Once the Ledger receives the full Journal entry, send the trade, postings, back to OrderManager, and shutdown
  *
  */
object TradeManager {
  def props(trade: Trade, side: TradeSide, account: Account) = Props(new TradeManager(trade, side, account))

  /** Receive this message when the trade has been persisted
    *
    */
  case object TradePersisted

  /** Receive this message when the posted state of the trade has been persisted
    *
    */
  case object TradePostedPersisted
}

class TradeManager(trade: Trade, side: TradeSide, account: Account) extends Actor with ActorLogging with Stash {
  val tradesColl = MongoFactory.database[BSONCollection](if (side == MAKER) "tradesMaker" else "tradesTaker")
  val query = BSONDocument("_id" -> trade._id)

  override def preStart(): Unit = {
    tradesColl.insert(trade).map { lastError =>
      self ! TradeManager.TradePersisted
      unstashAll()
    }
  }

  def receive: Receive = {
    case TradeManager.TradePersisted =>
      val tradePersister = sender()
      val myOrder = trade.orderBySide(side)
      val spent = myOrder.contract.getCashSpent(trade.price, trade.quantity)
      val denominatedDirection = if (myOrder.side == BUY) DEBIT else CREDIT
      val payoutDirection = if (myOrder.side == BUY) CREDIT else DEBIT
      val userDenominatedPosting = Posting(myOrder.contract.denominated.get, account, spent, denominatedDirection)
      val userPayoutPosting = Posting(myOrder.contract.payout.get, account, trade.quantity, payoutDirection)
      val uuid: UUID = trade.uuid
      val postingSet = Set(userDenominatedPosting, userPayoutPosting)
      postingSet.foreach(x => context.actorOf(Poster.props(4, x, uuid)))
      context.become(waitForPosted(tradePersister, postingSet, Set.empty))
  }

  def waitForPosted(tradePersister: ActorRef, postingsRemaining: Set[Posting], postingsPosted: Set[Posting]): Receive = {
    if (postingsRemaining.isEmpty) {
      tradesColl.update(query, trade.copy(posted=true)).map { lastError =>
        self ! TradeManager.TradePostedPersisted
      }
      waitForPersisted(postingsPosted)
    }
    else {
      LoggingReceive {
        case Poster.Posted(posting) =>
          context.become(waitForPosted(tradePersister, postingsRemaining - posting, postingsPosted + posting))
      }
    }
  }

  def waitForPersisted(postings: Set[Posting]): Receive = {
    case TradeManager.TradePostedPersisted =>
      context.parent ! OrderManager.TradePosted(trade, postings, side)
      self ! PoisonPill
  }
}


