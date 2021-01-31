package io.openledger.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.account.Account._
import io.openledger.account.AccountMode.{CREDIT, DEBIT}

case class Ready(accountId: String) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState =
    event match {
      case CreditAccountOpened(_) => CreditAccount(accountId, BigDecimal(0), BigDecimal(0), BigDecimal(0))
      case DebitAccountOpened(_) => DebitAccount(accountId, BigDecimal(0), BigDecimal(0), BigDecimal(0))
    }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now : TimeGen): Effect[AccountEvent, AccountState] =
    command match {
      case Open(mode) => mode match {
        case CREDIT =>
          Effect.persist(CreditAccountOpened(now()))
            .thenNoReply()
        case DEBIT =>
          Effect.persist(DebitAccountOpened(now()))
            .thenNoReply()
      }
    }
}
