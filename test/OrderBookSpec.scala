/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import models._
import org.scalatest.WordSpec

class OrderBookSpec extends WordSpec with Orders {
  def placeManyOrders(orderBook: OrderBook, orders: List[Order]) = {
    val folder = orders.foldLeft(orderBook, List[Order](), List[Trade]())_
    folder((x,y) => x._1.placeOrder(y) match {
      case (newBook, orderUpdates, trades) => (newBook, x._2 ++ orderUpdates, x._3 ++ trades)
    })
  }

  "An orderbook" when {
    "given one order" should {
      val (orderBook, orders, trades) = new OrderBook(btc).placeOrder(buy100At100)
      "have one order in it" in {
        assert(orders.size === 1)
      }
      "the order in the book should be the same" in {
        assert(orders.head === buy100At100)
      }
      "no trades result" in {
        assert(trades.size === 0)
      }
    }
    "given two orders on the same side" should {
      val (orderBook, orders, trades) = placeManyOrders(new OrderBook(btc), List(buy100At100, buy100At50))
      "have two orders in it" in {
        assert(orders.size === 2)
      }
      "the first order comes back first" in {
        assert(orders.head === buy100At100)
      }
      "the second order is second" in {
        assert(orders(1) === buy100At50)
      }
      "no trades result" in {
        assert(trades.size === 0)
      }
    }
    "given two orders on the same side, then one that matches both, but gets exhausted" should {
      val (orderBook, orders, trades) = placeManyOrders(new OrderBook(btc), List(buy100At100, buy100At50, sell100At50))
      "create four order updates" in {
        assert(orders.size === 4)
      }
      "the first order shows up first" in {
        assert(orders.head === buy100At100)
      }
      "the second order shows up second" in {
        assert(orders(1) === buy100At50)
      }
      "the third order is an updated version of the first one" in {
        assert(orders(2) !== buy100At100)
        assert(orders(2)._id === buy100At100._id)
      }
      "the third order update is the 1st one with qty 0" in {
        assert(orders(2) === buy100At100.copy(quantity = 0))
      }
      "the fourth update order is the sell with qty 0" in {
        assert(orders(3) === sell100At50.copy(quantity = 0))
      }
      "a single trade results" in {
        assert(trades.size === 1)
      }
    }
    "given two orders on the same side, then one that matches both, with enough qty" should {
      val (orderBook, orders, trades) = placeManyOrders(new OrderBook(btc), List(buy100At100, buy100At50, sell200At50))
      "five order updates result" in {
        assert(orders.size === 5)
      }
      "first order is the first one" in {
        assert(orders.head === buy100At100)
      }
      "second order is the second one" in {
        assert(orders(1) === buy100At50)
      }
      "the third order update is the 1st with qty 0" in {
        assert(orders(2) === buy100At100.copy(quantity = 0))
      }
      "the fourth order update now is the 2d with qty 0" in {
        assert(orders(3) === buy100At50.copy(quantity = 0))
      }
      "the fifth order update is the sell with qty 0" in {
        assert(orders(4) === sell200At50.copy(quantity = 0))
      }
      "two trades result" in {
        assert(trades.size === 2)
      }
      "first trade is qty 100, price 100" in {
        assert(trades.head.quantity === 100 && trades.head.price === 100)
      }
      "2d trade is qty 100, price 50" in {
        assert(trades(1).quantity === 100 && trades(1).price === 50)
      }
    }
  }
}

