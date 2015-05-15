/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package sputnik

import com.github.nscala_time.time.Imports._
import sputnik.BookSide._
import sputnik.ContractType._

trait Orders {
  val user = Account("test")
  val btc = Contract("BTC", None, None, 1000000, 100000, 100000000, CASH)
  val usd = Contract("USD", None, None, 10000, 100, 1000000, CASH)
  val btcusd = Contract("BTC/USD", Some(usd), Some(btc), 100, 1000000, 1, CASH_PAIR)

  val buy100At100 = Order(1, 100, 100, DateTime.now, BUY, user, btcusd)
  val buy100At50 = Order(2, 100, 50, DateTime.now, BUY, user, btcusd)

  val sell100At50 = Order(3, 100, 50, DateTime.now, SELL, user, btcusd)
  val sell100At150 = Order(4, 100, 150, DateTime.now, SELL, user, btcusd)

  val now = DateTime.now
  val sell100At100Now = Order(5, 100, 100, now, SELL, user, btcusd)
  val sell100At100In5Min = Order(6, 100, 100, now + 5.minutes, SELL, user, btcusd)

  val buy100At100Now = Order(7, 100, 100, now, BUY, user, btcusd)
  val buy100At100In5Min = Order(8, 100, 100, now + 5.minutes, BUY, user, btcusd)

  val sell200At50 = Order(9, 200, 50, DateTime.now, SELL, user, btcusd)

}
