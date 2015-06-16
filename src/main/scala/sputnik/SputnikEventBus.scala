package sputnik

import akka.util.Subclassification
import akka.actor.ActorRef
import akka.event.{EventBus, SubchannelClassification}

trait SputnikClassifier

case object GenericClassifier extends SputnikClassifier
case class OrderBookClassifier(contract: Option[Contract] = None) extends SputnikClassifier
case class TradeClassifier(contract: Option[Contract] = None, accountList: Set[Account] = Set[Account]()) extends SputnikClassifier
case class PostingClassifier(contract: Option[Contract] = None, account: Option[Account] = None) extends SputnikClassifier

object SputnikEventBus extends EventBus with SubchannelClassification {
  type Event = SputnikEvent
  type Classifier = SputnikClassifier
  type Subscriber = ActorRef

  override protected def classify(event: Event): Classifier = event match {
    case book: OrderBook =>
      OrderBookClassifier(Some(book.contract))
    case trade: Trade =>
      TradeClassifier(Some(trade.contract), Set[Account](trade.aggressiveOrder.account, trade.passiveOrder.account))
    case posting: Posting =>
      PostingClassifier(Some(posting.contract), Some(posting.account))

  }

  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = (x, y) match {
      case (TradeClassifier(xc, xa), TradeClassifier(yc, ya)) => (yc.toSet subsetOf xc.toSet) && (ya subsetOf xa)
      case (OrderBookClassifier(xc), OrderBookClassifier(yc)) => yc.toSet subsetOf xc.toSet
      case (PostingClassifier(xc, xa), PostingClassifier(yc, ya)) => (yc.toSet subsetOf xc.toSet) && (ya.toSet subsetOf xa.toSet)
      case (_, GenericClassifier) => true
      case _ => false
    }
  }

  override protected def publish(event: Event, subscriber: Subscriber): Unit =
    subscriber ! event
}
