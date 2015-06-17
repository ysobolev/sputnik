package models

import com.github.nscala_time.time.Imports._

case class Posting(contract: Contract,
                   account: Account,
                   quantity: Quantity,
                   direction: LedgerDirection.LedgerDirection,
                   timestamp: DateTime = DateTime.now) extends SputnikEvent {
  require(contract.contractType != ContractType.CASH_PAIR)

  lazy val sign = {
    val user_sign = account match {
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

