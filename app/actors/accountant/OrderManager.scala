package actors.accountant

import actors.{SputnikEventBus, Engine, MongoFactory}
import akka.actor._
import akka.event.LoggingReceive
import models.TradeSide._
import models._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import scala.concurrent.ExecutionContext.Implicits.global


object OrderManager {
  def props(order: Order, originator: ActorRef, account: Account): Props = Props(new OrderManager(order, originator, account))
  case class TradePosted(trade: Trade, postings: Set[Posting], side: TradeSide)
  case object CancelOrder
  case object OrderCancelled
  case object PlaceOrderPersisted
  case object OrderBooked
  case object StartTrading
  case class TradeNotify(trade: Trade, side: TradeSide)
  case class OrderPlaced(order: Order)
  case class UpdateOrder(quantity: Quantity)

  case class InvalidOrder(msg: Accountant.InvalidOrderTypes)
  case object ValidOrder
}

class OrderManager(originalOrder: Order, originator: ActorRef, account: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val ordersColl = MongoFactory.database[BSONCollection]("orders")
  val query = BSONDocument("_id" -> originalOrder._id)

  override def preStart() = {
    ordersColl.insert(originalOrder).map { lastError =>
      self ! OrderManager.PlaceOrderPersisted
      unstashAll()
    }
  }

  def receive: Receive = LoggingReceive {
    case OrderManager.PlaceOrderPersisted =>
      context.parent ! Accountant.ValidateOrder(originalOrder)
      unstashAll()
      context.become(awaitingValidation)
    case msg =>
      log.debug(s"Stashing $msg")
      stash()
  }

  def awaitingValidation: Receive = LoggingReceive {
    case OrderManager.ValidOrder =>
      val newOrder = originalOrder.copy(accepted = true)
      engineRouter ! Engine.PlaceOrder(newOrder)
      originator ! OrderManager.OrderPlaced(newOrder)
      ordersColl.update(query, newOrder)
      unstashAll()
      context.become(awaitingBooking)

    case OrderManager.InvalidOrder(msg) =>
      originator ! msg
      ordersColl.update(query, originalOrder.copy(cancelled = true)).map { lastError =>
        self ! PoisonPill
      }

    case msg =>
      log.debug(s"Stashing $msg")
      stash()
  }

  def awaitingBooking: Receive = LoggingReceive {
    case OrderManager.OrderBooked =>
      ordersColl.update(query, originalOrder.copy(booked = true)).map { lastError =>
        self ! OrderManager.StartTrading
      }
    case OrderManager.StartTrading =>
      unstashAll()
      context.become(trading(originalOrder))
    case msg =>
      log.debug("Stashing $msg")
      stash()
  }

  def trading(order: Order): Receive = {
    def updateAndBecome(newOrder: Order) = {
      ordersColl.update(query, newOrder)
      context.become(trading(newOrder))
    }

    if (order.quantity == 0L) {
      self ! PoisonPill
      LoggingReceive {
        case _ =>
      }
    }
    else LoggingReceive {
      case OrderManager.TradeNotify(t, side) =>
        context.actorOf(TradePoster.props(t, side, account))

      case OrderManager.TradePosted(trade, postings, side) =>
        val newOrder = trade.orderBySide(side)

        context.parent ! Accountant.TradePosted(trade, postings, newOrder)
        if (newOrder.quantity < order.quantity)
          updateAndBecome(newOrder)

      case OrderManager.CancelOrder =>
        engineRouter ! Engine.CancelOrder(order.contract, order._id)

      case OrderManager.OrderCancelled =>
        updateAndBecome(order.copy(cancelled = true))


    }
  }
}
