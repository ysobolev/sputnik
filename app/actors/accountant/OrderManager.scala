package actors.accountant

import actors.{SputnikEventBus, Engine, MongoFactory}
import akka.actor._
import akka.event.LoggingReceive
import models.TradeSide._
import models._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import scala.concurrent.ExecutionContext.Implicits.global

/** The OrderManager handles the lifecycle of an order
  *
  * * Receives the placeOrder request
  * * Checks with accountant to see if can be placed
  * * If not return error to the originator
  * * If it can be placed, place the order with the engine
  *
  * * On a trade notification from the engine:
  * * Spawn a TradeManager
  * * Wait for the TradeManager to return with a TradePosted
  * * Update the order with the trade
  * * Notify the accountant with the postings and the updated order
  */
object OrderManager {
  def props(order: Order, originator: ActorRef, account: Account): Props = Props(new OrderManager(order, originator, account))

  /** Receives notification that the trade has been posted, notify the accountant
    * with Accountant.TradePosted and modify the state of the order 
    * 
    * @param trade
    * @param postings
    * @param side
    */
  case class TradePosted(trade: Trade, postings: Set[Posting], side: TradeSide)

  /** Receives a request to cancel the order. Sends Engine.CancelOrder 
    * 
    */
  case object CancelOrder

  /** Receives notification that the order has been cancelled. Persist and shutdown
    * 
    */
  case object OrderCancelled

  /** Receives notification that the order has been persisted 
    * 
    */
  case object OrderPersisted

  /** Receives notification that the accepted state of the order has been persisted
    *
    */
  case class AcceptancePersisted(order: Order)

  /** Receives notification that the order has been placed in the orderbook 
    * 
    */
  case object OrderBooked

  /** Receives notification that the order's booking has been persisted. Transition to trading state.
    *
    */
  case object OrderBookedPersisted

  /** Receives notification that a trade has resulting from the order we care about. Creates a TradePoster
    * for this trade.
    * 
    * @param trade
    * @param side
    */
  case class TradeNotify(trade: Trade, side: TradeSide)

  /** We send this message to tell someone that an order has been placed
    * 
    * @param order
    */
  case class OrderPlaced(order: Order)

  /** Receives notification that the order is invalid
    * 
    * @param msg
    */
  case class InvalidOrder(msg: Accountant.InvalidOrderTypes)

  /** Receives notification that the order is valid, sends Engine.PlaceOrder to the engine
    * and OrderPlaced to the originator
    * 
    */
  case object ValidOrder
}

class OrderManager(originalOrder: Order, originator: ActorRef, account: Account) extends Actor with ActorLogging with Stash {
  val engineRouter = context.system.actorSelection("/user/engine")
  val ordersColl = MongoFactory.database[BSONCollection]("orders")
  val query = BSONDocument("_id" -> originalOrder._id)

  override def preStart() = {
    ordersColl.insert(originalOrder).map { lastError =>
      self ! OrderManager.OrderPersisted
      unstashAll()
    }
  }

  def receive: Receive = LoggingReceive {
    case OrderManager.OrderPersisted =>
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
      ordersColl.update(query, newOrder).map { lastError =>
        self ! OrderManager.AcceptancePersisted(newOrder)
      }
      unstashAll()
      context.become(awaitingPersistence)

    case OrderManager.InvalidOrder(msg) =>
      originator ! msg
      ordersColl.update(query, originalOrder.copy(cancelled = true)).map { lastError =>
        self ! PoisonPill
      }

    case msg =>
      log.debug(s"Stashing $msg")
      stash()
  }

  def awaitingPersistence: Receive = LoggingReceive {
    case OrderManager.AcceptancePersisted(order) =>
      engineRouter ! Engine.PlaceOrder(order)
      originator ! OrderManager.OrderPlaced(order)
      unstashAll()
      context.become(awaitingBooking)

    case msg =>
      log.debug(s"Stashing $msg")
      stash()
  }

  def awaitingBooking: Receive = LoggingReceive {
    case OrderManager.OrderBooked =>
      ordersColl.update(query, originalOrder.copy(booked = true)).map { lastError =>
        self ! OrderManager.OrderBookedPersisted
      }
    case OrderManager.OrderBookedPersisted =>
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
        context.actorOf(TradeManager.props(t, side, account))

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
