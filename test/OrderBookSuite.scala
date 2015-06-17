/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import models._
import org.scalatest.FunSuite

class OrderBookSuite extends FunSuite with Orders {
  def placeManyOrders(orderBook: OrderBook, orders: List[Order]) = {
    val folder = orders.foldLeft(orderBook, List[Order](), List[Trade]())_
    folder((x,y) => x._1.placeOrder(y) match {
      case (newBook, orderUpdates, trades) => (newBook, x._2 ++ orderUpdates, x._3 ++ trades)
    })
  }

  test("place one order") {
    val (orderBook, orders, trades) = new OrderBook(btc).placeOrder(buy100At100)
    assert(orders.size === 1, "one order results")
    assert(orders.head === buy100At100, "the order is the same")
    assert(trades.size === 0, "no trades result")
  }

  test("place two orders on the same side") {
    val (orderBook, orders, trades) = placeManyOrders(new OrderBook(btc), List(buy100At100, buy100At50))
    assert(orders.size === 2, "two orders result")
    assert(orders.head === buy100At100, "first order is the first one")
    assert(orders(1) === buy100At50, "second order is the second one")
    assert(trades.size === 0, "no trades result")
  }

  test("place two orders on the same side, then one that matches both, but gets exhausted") {
    val (orderBook, orders, trades) = placeManyOrders(new OrderBook(btc), List(buy100At100, buy100At50, sell100At50))
    assert(orders.size === 4, "four order updates result")
    assert(orders.head === buy100At100, "first order is the first one")
    assert(orders(1) === buy100At50, "second order is the second one")
    assert(orders(2) !== buy100At100, "the third order is an updated version of the first one")
    assert(orders(2) === buy100At100.copy(quantity = 0), "the third order update now is the 1st with qty 0")
    assert(orders(3) === sell100At50.copy(quantity = 0), "the fourth order update now is the sell with qty 0")

    assert(trades.size === 1, "a single trade results")
  }

  test("place two orders on the same side, then one that matches both, with enough qty") {
    val (orderBook, orders, trades) = placeManyOrders(new OrderBook(btc), List(buy100At100, buy100At50, sell200At50))
    assert(orders.size === 5, "five order updates result")
    assert(orders.head === buy100At100, "first order is the first one")
    assert(orders(1) === buy100At50, "second order is the second one")
    assert(orders(2) === buy100At100.copy(quantity = 0), "the third order update now is the 1st with qty 0")
    assert(orders(3) === buy100At50.copy(quantity = 0), "the fourth order update now is the 2d with qty 0")
    assert(orders(4) === sell200At50.copy(quantity = 0), "the fifth order now is the sell with qty 0")

    assert(trades.size === 2, "two trades result")
    assert(trades.head.quantity === 100 && trades.head.price === 100, "first trade is qty 100, price 100")
    assert(trades(1).quantity === 100 && trades(1).price === 50, "2d trade is qty 100, price 50")
  }


}

