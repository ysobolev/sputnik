package sputnik

import akka.actor.{Props, ActorRef, Actor}
import scala.reflect.ClassTag

trait GetOrCreateChild extends Actor {
  def getOrCreateChild[KeyType <: Nameable](key: KeyType)(implicit factory: (KeyType) => Props) = {
    context.child(key.name) match {
      case Some(actor: ActorRef) => actor
      case None => context.actorOf(factory(key), name = key.name)
    }
  }
}