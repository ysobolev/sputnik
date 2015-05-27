package sputnik

import com.mongodb.casbah.{MongoClient, MongoClientURI}
import com.typesafe.config.ConfigFactory

object MongoFactory {
/*  private val config = ConfigFactory.load()
  private val DATABASE = config.getString("mongo.db")
  private val server = MongoClientURI(config.getString("mongo.uri"))*/
  private val client = MongoClient("localhost")
  val database = client("sputnik")
}

