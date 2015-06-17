package models

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
