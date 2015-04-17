package sputnik

import com.github.nscala_time.time.Imports._
import sputnik.sputnik.BookSide._

trait Orders {
  val buy100At100 = Order(1, 100, 100, DateTime.now, BUY, "test")
  val buy100At50 = Order(2, 100, 50, DateTime.now, BUY, "test")

  val sell100At50 = Order(3, 100, 50, DateTime.now, SELL, "test")
  val sell100At150 = Order(4, 100, 150, DateTime.now, SELL, "test")

  val now = DateTime.now
  val sell100At100Now = Order(5, 100, 100, now, SELL, "test")
  val sell100At100In5Min = Order(6, 100, 100, now + 5.minutes, SELL, "test")

  val buy100At100Now = Order(7, 100, 100, now, BUY, "test")
  val buy100At100In5Min = Order(8, 100, 100, now + 5.minutes, BUY, "test")

  val sell200At50 = Order(9, 200, 50, DateTime.now, SELL, "test")

}
