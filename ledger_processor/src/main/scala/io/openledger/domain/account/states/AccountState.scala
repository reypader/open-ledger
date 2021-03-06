package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerSerializable
import io.openledger.domain.account.Account.{AccountCommand, EntryMessenger}
import io.openledger.events._

trait AccountState extends LedgerSerializable {
  def accountId: String

  def availableBalance: BigDecimal

  def currentBalance: BigDecimal

  def handleEvent(event: AccountEvent)(implicit
      context: ActorContext[AccountCommand]
  ): PartialFunction[AccountEvent, AccountState]

  def handleCommand(command: AccountCommand)(implicit
      context: ActorContext[AccountCommand],
      entryMessenger: EntryMessenger,
      now: TimeGen
  ): PartialFunction[AccountCommand, Effect[AccountEvent, AccountState]]
}
