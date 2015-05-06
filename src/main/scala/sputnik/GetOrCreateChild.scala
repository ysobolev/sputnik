package sputnik

import akka.actor.{Props, ActorRef, Actor}
import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.ClassTag

trait GetOrCreateChild extends Actor {
  def getOrCreateChild[KeyType <: Nameable, ValueType <: Actor: ClassTag](factory: (KeyType) => ValueType)(key: KeyType) = {
    context.child(key.name) match {
      case Some(actor: ActorRef) => actor
      case None => context.actorOf(Props(factory(key)), name = key.name)
    }
  }
}