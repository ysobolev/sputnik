package sputnik

import java.util.UUID

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import sputnik.LedgerSide._
import sputnik.LedgerDirection._
import sputnik.TradeSide._
import sputnik.BookSide._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.collection.mutable
import scala.concurrent.duration._

class AccountantException(x: String) extends Exception(x)
abstract class AccountantMessage

case class Account(name: String, side: LedgerSide = LIABILITY)

class Accountant(name: Account) extends Actor with ActorLogging {
  val positions = mutable.Map[Contract, Int]().withDefaultValue(0)
  val engine = context.system.actorSelection("/user/engine")
  val ledger = context.system.actorSelection("/user/ledger")
  val orderMap = mutable.Map[Int, Order]()
  val pendingPostings = mutable.Map[UUID, List[Posting]]()

  implicit val timeout = Timeout(5, SECONDS)
  val balanceFuture = ledger ? GetBalances(name)
  balanceFuture.mapTo[Map[Contract, Int]].map(_.foreach((x) => positions.update(x._1, x._2)))

  def checkMargin(order: Order) = true

  def post(posting: NewPosting) = {
    ledger ! posting
    pendingPostings(posting.uuid) = posting.posting :: pendingPostings.getOrElse(posting.uuid, List[Posting]())
  }

  def receive = {
    case TradeNotify(t, side) =>
      log.info(s"TradeNotify($t, $side)")
      val myOrder = if (side == MAKER) t.passiveOrder else t.aggressiveOrder
      val spent = myOrder.contract.getCashSpent(t.price, t.quantity)
      val denominatedDirection = if (myOrder.side == BUY) DEBIT else CREDIT
      val payoutDirection = if (myOrder.side == BUY) CREDIT else DEBIT
      val userDenominatedPosting = Posting(myOrder.contract.denominated.get, name, t.quantity, denominatedDirection)
      val userPayoutPosting = Posting(myOrder.contract.payout.get, name, spent, payoutDirection)
      val uuid: UUID = t.uuid
      post(NewPosting(4, userDenominatedPosting, uuid))
      post(NewPosting(4, userPayoutPosting, uuid))

    case PostingResult(uuid, true) =>
      log.info(s"PostingsResult($uuid, true)")
      val pendingForUuid = pendingPostings.getOrElse(uuid, List[Posting]())
      pendingForUuid.foreach(p => positions(p.contract) += p.signedQuantity)
      pendingPostings -= uuid

    case OrderUpdate(o) =>
      log.info(s"OrderUpdate($o)")
      orderMap(o.id) = o
    case PlaceOrder(o) =>
      log.info(s"PlaceOrder($o)")
      if (checkMargin(o)) {
        val result = engine ask PlaceOrder(o)
      }
      else {
        throw new AccountantException("Insufficient Margin")
      }
    case _ =>
  }
}
