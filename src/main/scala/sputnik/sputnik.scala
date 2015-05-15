import akka.actor.ActorLogging
import com.github.nscala_time.time.Imports._

package object sputnik {
  type Quantity = Int
  type Price = Int
  type Positions = Map[Contract, Quantity]

  object BookSide extends Enumeration {
    type BookSide = Value
    val BUY, SELL = Value
  }

  object LedgerSide extends Enumeration {
    type LedgerSide = Value
    val ASSET, LIABILITY = Value
  }

  object LedgerDirection extends Enumeration {
    type LedgerDirection = Value
    val DEBIT, CREDIT = Value
  }

  object TradeSide extends Enumeration {
    type TradeSide = Value
    val MAKER, TAKER = Value
  }

  object ContractType extends Enumeration {
    type ContractType = Value
    val CASH, CASH_PAIR, FUTURES, PREDICTION = Value
  }

  trait Nameable {
    def name: String
  }

  case class Account(name: String, side: LedgerSide.LedgerSide = LedgerSide.LIABILITY) extends Nameable

  case class Contract(ticker: String, denominated: Option[Contract], payout: Option[Contract], tickSize: Int,
                      lotSize: Int, denominator: Int,
                      contractType: ContractType.ContractType) extends Nameable {
    contractType match {
      case ContractType.CASH =>
      case _ => if (payout.isEmpty || denominated.isEmpty) throw new Exception("Can't create non-CASH without denominated and payout")
    }

    def getCashSpent(price: Price, quantity: Quantity): Quantity = {
      val pBig: BigInt = price
      val qBig: BigInt = quantity
      contractType match {
        case ContractType.CASH_PAIR => (qBig * pBig / (denominator * payout.get.denominator)).toInt
        case _ => (qBig * pBig * lotSize / denominator).toInt
      }
    }

    def priceToWire(price: BigDecimal): Price = {
      val p = contractType match {
        case ContractType.CASH_PAIR => price * denominated.get.denominator * denominator
        case _ => price * denominator
      }
      (p - p % tickSize).toInt
    }

    def quantityToWire(quantity: BigDecimal): Quantity = {
      val p = contractType match {
        case ContractType.CASH => quantity * denominator
        case ContractType.FUTURES => quantity
        case ContractType.PREDICTION => quantity
        case ContractType.CASH_PAIR =>
          val q = quantity * payout.get.denominator
          q - q % lotSize
      }
      p.toInt
    }

    val name = ticker.replace("/", "")
  }


  case class Posting(contract: Contract, user: Account, quantity: Quantity, direction: LedgerDirection.LedgerDirection) {
    require(contract.contractType != ContractType.CASH_PAIR)
    val timestamp = DateTime.now

    lazy val sign = {
      val user_sign = user match {
        case Account(_, LedgerSide.ASSET) => 1
        case Account(_, LedgerSide.LIABILITY) => -1
      }
      val dir_sign = direction match {
        case LedgerDirection.DEBIT => 1
        case LedgerDirection.CREDIT => -1
      }
      user_sign * dir_sign
    }
    lazy val signedQuantity = sign * quantity
  }
}
