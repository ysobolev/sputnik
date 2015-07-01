package actors

import actors.Engine.SetAccountantRouter
import actors.EngineRouter._
import akka.actor._
import models._

object EngineRouter {
  def props: Props = Props(new EngineRouter)

  sealed trait State
  case object Trading extends State
  case object Initializing extends State

  sealed trait Data
  case class Initialized(accountantRouter: ActorRef) extends Data
  case object Uninitialized extends Data
}

class EngineRouter extends LoggingFSM[State, Data] with Stash with GetOrCreateChild {
  implicit def childFactory(c: Contract): Props = Engine.props(c)
  startWith(Initializing, Uninitialized)

  when(Initializing) {
    case Event(SetAccountantRouter(router), Uninitialized) =>
      goto(Trading) using Initialized(router)
    case _ =>
      stash()
      stay
  }

  onTransition {
    case Initializing -> Trading =>
      unstashAll()
  }

  when(Trading) {
    case Event(Engine.PlaceOrder(order), Initialized(router)) =>
      getOrCreateChild(order.contract, actor => actor ! SetAccountantRouter(router)).tell(Engine.PlaceOrder(order), sender())
      stay

    case Event(Engine.CancelOrder(contract, id), Initialized(router)) =>
      getOrCreateChild(contract, actor => actor ! SetAccountantRouter(router)).tell(Engine.CancelOrder(contract, id), sender())
      stay
  }
}
