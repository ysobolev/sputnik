package sputnik

import reactivemongo.api._
import scala.concurrent.ExecutionContext.Implicits.global

object MongoFactory {
  private val driver = new MongoDriver
  private val connection = driver.connection(List("localhost"))
  val database = connection("sputnik")
}

