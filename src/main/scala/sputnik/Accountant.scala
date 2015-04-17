package sputnik

import akka.actor.{Actor, ActorLogging}
import sputnik.sputnik.LedgerSide._

import scala.collection.mutable

case class User(username: String, side: LedgerSide)

class Accountant(user: User) extends Actor with ActorLogging {
  val engine = context.system.actorSelection("/user/engine")
  val orderMap = mutable.Map[Int, Order]()
  def receive = {
    case t: Trade =>
      log.info(t.toString)
    case o: Order =>
      log.info(o.toString)
      orderMap(o.id) = o
    case _ =>
  }
}
