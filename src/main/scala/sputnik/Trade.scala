package sputnik
import sputnik._
import com.github.nscala_time.time.Imports._
import java.util.UUID

case class Trade(aggressiveOrder: Order, passiveOrder: Order, quantity: Quantity, price: Price) {
  val timestamp = DateTime.now
  val uuid = UUID.randomUUID()
}
