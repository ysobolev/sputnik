package models

case class Account(name: String, side: LedgerSide.LedgerSide = LedgerSide.LIABILITY) extends Nameable
