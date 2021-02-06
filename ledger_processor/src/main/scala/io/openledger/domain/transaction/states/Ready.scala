package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class Ready(transactionId: String) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case Started(entryCode, accountToDebit, accountToCredit, amount, authOnly) => Authorizing(entryCode, transactionId, accountToDebit, accountToCredit, amount, authOnly)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in Ready")
    command match {
      case Begin(entryCode, accountToDebit, accountToCredit, amount, authOnly) =>
        Effect.persist(Started(entryCode, accountToDebit, accountToCredit, amount, authOnly))
          .thenRun(_.proceed())
      case _ =>
        context.log.warn(s"Unhandled $command in Ready")
        Effect.none
    }
  }


  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Doing nothing on Ready")
  }
}