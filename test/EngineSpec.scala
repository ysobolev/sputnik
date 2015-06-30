/**
 * Created by sameer on 6/30/15.
 */

import akka.actor.ActorSystem
import models._
import actors.{OrderBookClassifier, SputnikEventBus, Engine}
import org.scalatest.{WordSpecLike, WordSpec}
import akka.testkit._

class EngineSpec extends TestKit(ActorSystem("testSystem")) with WordSpecLike with Orders {
  "the engine" when {
    "instantiated" should {
      val engine = TestFSMRef(new Engine(btcusd))
      "have the right type" in {
        val mustByTypedProperly: TestActorRef[Engine] = engine
      }
      "start with empty orderbook" in {
        assert(engine.stateName == Engine.Trading)
        assert(engine.stateData == new OrderBook(btcusd))
      }
    }
    "given a single order" should {
      val engine = TestFSMRef(new Engine(btcusd))
      SputnikEventBus.subscribe(testActor, OrderBookClassifier(Some(btcusd)))
      engine ! Engine.PlaceOrder(buy100At100)
      "have that order in the book" in {
        assert(engine.stateData.bids contains buy100At100)
        assert(engine.stateData.asks.isEmpty)
      }
      "publish the new orderbook" in {
        expectMsg(engine.stateData)
      }
    }
    "given two orders that match" should {
      val engine = TestFSMRef(new Engine(btcusd))
      SputnikEventBus.subscribe(testActor, OrderBookClassifier(Some(btcusd)))
      engine ! Engine.PlaceOrder(buy100At100)
      engine ! Engine.PlaceOrder(sell100At50)
      "have an empty book" in {
        assert(engine.stateData.bids.isEmpty)
        assert(engine.stateData.asks.isEmpty)
      }
      "publish two orderbooks" in {
        val msgs = receiveN(2)
        assert(msgs(1) == engine.stateData)
      }
    }
  }
}
