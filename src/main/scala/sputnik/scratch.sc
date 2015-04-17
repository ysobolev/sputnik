
import sputnik._
import com.github.nscala_time.time.Imports._

val orderBook = new OrderBook()
DateTime.now
orderBook.placeOrder(Order(1, 100, 100, DateTime.now, "BUY", "blah"))

def placeManyOrders(orders: List[Order]) = {
  val folder = orders.foldLeft(orderBook, List[Order](), List[Trade]())_
  folder((x,y) => x._1.placeOrder(y) match {
    case (newBook, orderUpdates, trades) => (newBook, x._2 ++ orderUpdates, x._3 ++ trades)
  })
}

val (x, y, z) = placeManyOrders(List(Order(1, 100, 100, DateTime.now, "BUY", "blah"),
                     Order(2, 100, 50, DateTime.now, "BUY", "blah"),
                     Order(3, 100, 150, DateTime.now, "SELL", "blah"),
                     Order(4, 50, 75, DateTime.now, "SELL", "blah")))




Order(1, 100, 100, DateTime.now, "BUY", "blah") matches Order(4, 50, 75, DateTime.now, "SELL", "blah")