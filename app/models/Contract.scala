package models

import actors.MongoFactory
import play.api.libs.json.Json
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{BSONDocumentReader, BSONDocumentWriter, BSONDocument}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Contract {

  implicit object ContractWriter extends BSONDocumentWriter[Contract] {
    def write(contract: Contract): BSONDocument = BSONDocument(
      "ticker" -> contract.ticker,
      "denominated" -> contract.denominated.map(write),
      "payout" -> contract.payout.map(write),
      "tickSize" -> contract.tickSize,
      "lotSize" -> contract.lotSize,
      "denominator" -> contract.denominator,
      "contractType" -> contract.contractType.toString
    )
  }

  implicit object ContractReader extends BSONDocumentReader[Contract] {
    def read(doc: BSONDocument): Contract = Contract(
      doc.getAs[String]("ticker").get,
      doc.getAs[Contract]("denominated"),
      doc.getAs[Contract]("payout"),
      doc.getAs[Long]("tickSize").get,
      doc.getAs[Long]("lotSize").get,
      doc.getAs[Long]("denominator").get,
      ContractType withName doc.getAs[String]("contractType").get
    )
  }

  implicit def getContracts: Future[List[Contract]] = {
    val contractsColl = MongoFactory.database[BSONCollection]("contracts")
    contractsColl.find(BSONDocument()).cursor[Contract].collect[List]()
  }

  implicit def getContract(ticker: String): Future[Contract] = {
    val contractsColl = MongoFactory.database[BSONCollection]("contracts")
    val contractsFuture = contractsColl.find(BSONDocument("ticker" -> ticker)).cursor[Contract].collect[List]()
    contractsFuture.map {
      case l: List[Contract] if l.size == 1 =>
        l.head
      case _ =>
        throw new StringIndexOutOfBoundsException(s"Contract $ticker not found")
    }
  }
  implicit val contractFormat = Json.format[Contract]

}

case class Contract(ticker: String, denominated: Option[Contract], payout: Option[Contract], tickSize: Long,
                    lotSize: Long, denominator: Long,
                    contractType: ContractType.ContractType) extends Nameable {
  contractType match {
    case ContractType.CASH =>
    case _ => if (payout.isEmpty || denominated.isEmpty) throw new Exception("Can't create non-CASH without denominated and payout")
  }

  def getCashSpent(price: Price, quantity: Quantity): Quantity = {
    contractType match {
      case ContractType.CASH_PAIR => quantity * price / (denominator * payout.get.denominator)
      case _ => quantity * price * lotSize / denominator
    }
  }

  def priceToWire(price: BigDecimal): Price = {
    val p = contractType match {
      case ContractType.CASH_PAIR => price * denominated.get.denominator * denominator
      case _ => price * denominator
    }
    (p - p % tickSize).toLongExact
  }

  def quantityToWire(quantity: BigDecimal): Quantity = {
    val q = contractType match {
      case ContractType.CASH => quantity * denominator
      case ContractType.FUTURES => quantity
      case ContractType.PREDICTION => quantity
      case ContractType.CASH_PAIR =>
        val q = quantity * payout.get.denominator
        q - q % lotSize
    }
    q.toLongExact
  }

  val name = ticker.replace("/", "")
}
