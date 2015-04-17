package sputnik

import org.scalatest.FunSuite
import com.github.nscala_time.time.Imports._

/**
 * Created by sameer on 4/17/15.
 */

class OrderSuite extends FunSuite {
  val buy100At100 = Order(1, 100, 100, DateTime.now, "BUY", "test")
  val buy100At50 = Order(1, 100, 50, DateTime.now, "BUY", "test")

  val sell100At50 = Order(2, 100, 50, DateTime.now, "SELL", "test")
  val sell100At150 = Order(3, 100, 150, DateTime.now, "SELL", "test")

  val now = DateTime.now
  val sell100At100Now = Order(4, 100, 100, now, "SELL", "test")
  val sell100At100In5Min = Order(5, 100, 100, now + 5.minutes, "SELL", "test")

  val buy100At100Now = Order(4, 100, 100, now, "BUY", "test")
  val buy100At100In5Min = Order(5, 100, 100, now + 5.minutes, "BUY", "test")

  test("matches correctly") {
    assert(buy100At100 matches sell100At50, "Buy 100@100 matches Sell 100@50")
    assert(!(buy100At100 matches sell100At150), "Buy 100@100 does not match Sell 100@150")
    assert(!(sell100At50 matches sell100At150), "Same side does not match itself")
  }

  test("orders correctly") {
    intercept[OrderException] {
      buy100At100 < sell100At50
    }
    assert(sell100At50 < sell100At150, "sell 50 < sell 150")
    assert(buy100At100 < buy100At50, "buy 100 < buy 50")
    assert(sell100At100Now < sell100At100In5Min, "sell now < sell later")
    assert(buy100At100Now < buy100At100In5Min, "buy now < buy later")
  }

}
