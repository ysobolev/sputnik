package controllers

import akka.actor.Status.Failure
import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import models._
import actors._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.actor._
import javax.inject._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent._
import akka.util.Timeout

import akka.actor.{ Actor, DeadLetter, Props }

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

  // DeadLetter Listener
  val listener = system.actorOf(Props(classOf[DeadLetterListener]))
  system.eventStream.subscribe(listener, classOf[DeadLetter])

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def orderBookSocket(ticker: String) = WebSocket.acceptWithActor[JsValue, JsValue] { request => out =>
    OrderBookSocketActor.props(out, ticker)
  }

  def getContracts = Action.async {
    models.getContracts.map(list => Ok(Json.toJson(list)))
  }

  def placeOrder = Action.async { implicit request =>
    request.body.asJson.get.validate[IncomingOrder] match {
      case success: JsSuccess[IncomingOrder] =>
        val incomingOrder = success.get
        implicit val timeout: Timeout = 5.seconds

        val res = for {
          order <- incomingOrder.toOrder
          placeOrderResult <- accountantRouter ? Accountant.PlaceOrder(order)
        } yield placeOrderResult
        res.map {
          case Accountant.OrderPlaced(order) =>
            Created(Json.toJson(order))
          case Accountant.InsufficientMargin =>
            BadRequest("Insufficient Margin")
        }
      case JsError(error) =>
        val p = Promise[Result]
        p.success(BadRequest("Validation failed"))
        p.future
    }

  }

}
