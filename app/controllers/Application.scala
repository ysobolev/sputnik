package controllers

import actors.Accountant.OrderMapClean
import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import models._
import actors._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.actor._
import javax.inject._
import akka.pattern.ask
import reactivemongo.bson.BSONObjectID
import scala.concurrent.duration._
import scala.concurrent._
import akka.util.Timeout
import actors.Accountant

import akka.actor.{ Actor, DeadLetter, Props }

import scala.util.Success

class DeadLetterListener extends Actor {
  def receive = {
    case d: DeadLetter => println(d)
  }
}


@Singleton
class Application @Inject() (system: ActorSystem) extends Controller {
  val accountantRouter = system.actorOf(AccountantRouter.props, name = "accountant")
  val engineRouter = system.actorOf(EngineRouter.props, name = "engine")
  val ledger = system.actorOf(Ledger.props, name = "ledger")
  implicit val timeout: Timeout = 5.seconds

  // DeadLetter Listener
  val listener = system.actorOf(Props(classOf[DeadLetterListener]))
  system.eventStream.subscribe(listener, classOf[DeadLetter])

  engineRouter ! Engine.SetAccountantRouter(accountantRouter)

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def orderBookSocket(ticker: String) = WebSocket.acceptWithActor[JsValue, JsValue] { request => out =>
    OrderBookSocketActor.props(out, ticker)
  }

  def tradesByAccountSocket(account: String) = WebSocket.acceptWithActor[JsValue, JsValue] { request => out =>
    TradeSocketActor.props(out, account = Some(account), contract = None)
  }

  def tradesByContractSocket(contract: String) = WebSocket.acceptWithActor[JsValue, JsValue] { request => out =>
    TradeSocketActor.props(out, account = None, contract = Some(contract))
  }

  def getContracts = Action.async {
    Contract.getContracts.map(list => Ok(Json.toJson(list)))
  }

  implicit val orderMapCleanWrites = new Writes[OrderMapClean] {
    def writes(oMap: OrderMapClean): JsValue = {
      val map = oMap.map {
        case (id: BSONObjectID, o: Order) => id.stringify -> Json.toJson(o)
      }
      Json.toJson(map)
    }
  }
  implicit val positionsWrites = new Writes[Positions] {
    def writes(p: Positions): JsValue = {
      val map = p.map {
        case (c: Contract, q: Quantity) => c.ticker -> q
      }
      Json.toJson(map)
    }
  }

  def getPositions(accountName: String) = Action.async {
    for {
      account <- Account.getAccount(accountName)
      positions<- (accountantRouter ? Accountant.GetPositions(account)).mapTo[Positions]
    } yield Ok(Json.toJson(positions))
  }

  def getOrders(accountName: String) = Action.async {
    for {
      account <- Account.getAccount(accountName)
      orders <- (accountantRouter ? Accountant.GetOrders(account)).mapTo[OrderMapClean]
    } yield Ok(Json.toJson(orders))
  }

  def getOrder(accountName: String, id: String) = Action.async {
    val bsonID = BSONObjectID(id)
    for {
      account <- Account.getAccount(accountName)
      order <- (accountantRouter ? Accountant.GetOrder(account, bsonID)).mapTo[Order]
    } yield Ok(Json.toJson(order))
  }

  def cancelOrder(accountName: String, id: String) = Action.async {
    val bsonID = BSONObjectID(id)
    Account.getAccount(accountName).map {
      account => accountantRouter ! Accountant.CancelOrder(account, bsonID)
    }
    Future { NoContent }
  }


  def placeOrder = Action.async { implicit request =>
    request.body.asJson.get.validate[IncomingOrder] match {
      case success: JsSuccess[IncomingOrder] =>
        val incomingOrder = success.get

        val res = for {
          order <- incomingOrder.toOrder
          placeOrderResult <- accountantRouter ? Accountant.PlaceOrder(order)
        } yield placeOrderResult
        res.map {
          case Accountant.OrderPlaced(order) =>
            Created(Json.toJson(order))
          case Accountant.InsufficientMargin =>
            BadRequest("Insufficient Margin")
          case Accountant.InvalidOrder =>
            BadRequest("Invalid Order")
        }
      case JsError(error) =>
        Future { BadRequest("Validation failed") }
    }

  }

}
