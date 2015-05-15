/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package sputnik

import org.scalatest.FunSuite

class OrderSuite extends FunSuite with Orders {

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
