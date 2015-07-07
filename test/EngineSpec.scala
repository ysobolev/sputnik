/**
 * Created by sameer on 6/30/15.
 */

import actors.accountant.Accountant
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
      "start in unintialized state" in {
        assert(engine.stateName == Engine.Initializing)
        assert(engine.stateData == Engine.Uninitialized)
      }
    }
    "given an accountantrouter" should {
      val engine = TestFSMRef(new Engine(btcusd))
      val accountantProbe = TestProbe()
      engine ! Engine.SetAccountantRouter(accountantProbe.ref)
      "be now in trading state" in {
        assert(engine.stateName == Engine.Trading)
        assert(engine.stateData == Engine.Initialized(new OrderBook(btcusd), accountantProbe.ref))
      }
    }
    "given a single order" should {
      val engine = TestFSMRef(new Engine(btcusd))
      val accountantProbe = TestProbe()
      engine ! Engine.SetAccountantRouter(accountantProbe.ref)
      SputnikEventBus.subscribe(testActor, OrderBookClassifier(Set(btcusd)))
      engine ! Engine.PlaceOrder(buy100At100)
      "have that order in the book" in {
        val book = engine.stateData match {
          case Engine.Initialized(orderBook: OrderBook, _) => orderBook
        }
        assert(book.bids contains buy100At100)
        assert(book.asks.isEmpty)
      }
      "publish the new orderbook" in {
        val book = engine.stateData match {
          case Engine.Initialized(orderBook: OrderBook, _) => orderBook
        }
        expectMsg(book)
      }
      "send no msgs to accountant" in {
        accountantProbe.expectNoMsg()
      }
    }
    "given two orders that match" should {
      val engine = TestFSMRef(new Engine(btcusd))
      val accountantProbe = TestProbe()
      engine ! Engine.SetAccountantRouter(accountantProbe.ref)
      SputnikEventBus.subscribe(testActor, OrderBookClassifier(Set(btcusd)))
      engine ! Engine.PlaceOrder(buy100At100)
      engine ! Engine.PlaceOrder(sell100At50)
      "have an empty book" in {
        val book = engine.stateData match {
          case Engine.Initialized(orderBook: OrderBook, _) => orderBook
        }
        assert(book.bids.isEmpty)
        assert(book.asks.isEmpty)
      }
      "publish two orderbooks" in {
        val msgs = receiveN(2)
        val book = engine.stateData match {
          case Engine.Initialized(orderBook: OrderBook, _) => orderBook
        }
        assert(msgs(1) == book)
      }
      "send the trade to accountant" in {
        val msg = accountantProbe.receiveN(1)(0)
        msg match {
          case Accountant.TradeNotify(trade: Trade, _) =>
            assert(trade.quantity == 100L)
            assert(trade.price == 100L)
        }
      }
    }
    "given an order and a cancel" should {
      val engine = TestFSMRef(new Engine(btcusd))
      val accountantProbe = TestProbe()
      engine ! Engine.SetAccountantRouter(accountantProbe.ref)
      SputnikEventBus.subscribe(testActor, OrderBookClassifier(Set(btcusd)))
      engine ! Engine.PlaceOrder(buy100At100)
      engine ! Engine.CancelOrder(btcusd, buy100At100._id)
      "have an empty book" in {
        val book = engine.stateData match {
          case Engine.Initialized(orderBook: OrderBook, _) => orderBook
        }
        assert(book.bids.isEmpty)
        assert(book.asks.isEmpty)
      }
      "publish two orderbooks" in {
        val msgs = receiveN(2)
        val book = engine.stateData match {
          case Engine.Initialized(orderBook: OrderBook, _) => orderBook
        }
        assert(msgs.head.asInstanceOf[OrderBook].bids contains buy100At100)
        assert(msgs(1) == book)
      }
    }

  }
}
