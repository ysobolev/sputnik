package controllers

import play.api._
import play.api.libs.json.JsValue
import play.api.mvc._
import play.api.Play.current

class Application extends Controller {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def orderBookSocket(ticker: String) = WebSocket.acceptWithActor[JsValue, JsValue] { request => out =>
    OrderBookSocketActor.props(out, ticker)
  }

}
