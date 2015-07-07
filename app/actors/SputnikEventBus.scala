package actors

import akka.util.Subclassification
import akka.actor.ActorRef
import akka.event.{EventBus, SubchannelClassification}
import models.OrderBook
import models._

trait SputnikClassifier
trait SputnikClassifierContractAccount extends SputnikClassifier {
  val contract: Set[Contract]
  val account: Set[Account]
}

case object GenericClassifier extends SputnikClassifier
case class OrderBookClassifier(contract: Set[Contract] = Set.empty, account: Set[Account] = Set.empty) extends SputnikClassifierContractAccount
case class OrderClassifier(contract: Set[Contract] = Set.empty, account: Set[Account] = Set.empty) extends SputnikClassifierContractAccount
case class TradeClassifier(contract: Set[Contract] = Set.empty, account: Set[Account] = Set.empty) extends SputnikClassifierContractAccount
case class PostingClassifier(contract: Set[Contract] = Set.empty, account: Set[Account] = Set.empty) extends SputnikClassifierContractAccount

object SputnikEventBus extends EventBus with SubchannelClassification {
  type Event = SputnikEvent[FeedMsg]
  type Classifier = SputnikClassifier
  type Subscriber = ActorRef

  override protected def classify(event: Event): Classifier = event match {
    case book: OrderBook =>
      OrderBookClassifier(Set[Contract](book.contract))
    case trade: Trade =>
      TradeClassifier(Set[Contract](trade.contract), Set[Account](trade.aggressiveOrder.account, trade.passiveOrder.account))
    case posting: Posting =>
      PostingClassifier(Set[Contract](posting.contract), Set[Account](posting.account))
    case order: Order =>
      OrderClassifier(Set[Contract](order.contract), Set[Account](order.account))
  }

  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = (x, y) match {
      case (TradeClassifier(xc, xa), TradeClassifier(yc, ya)) => (yc subsetOf xc) && (ya subsetOf xa)
      case (OrderBookClassifier(xc, _), OrderBookClassifier(yc, _)) => yc subsetOf xc
      case (PostingClassifier(xc, xa), PostingClassifier(yc, ya)) => (yc subsetOf xc) && (ya subsetOf xa)
      case (OrderClassifier(xc, xa), OrderClassifier(yc, ya)) => (yc subsetOf xc) && (ya subsetOf xa)
      case (_, GenericClassifier) => true
      case _ => false
    }
  }

  override protected def publish(event: Event, subscriber: Subscriber): Unit =
    subscriber ! event
}
