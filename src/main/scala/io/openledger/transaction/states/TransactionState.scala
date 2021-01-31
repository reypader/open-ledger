package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.JsonSerializable
import io.openledger.transaction.Transaction.{AccountMessenger, ResultMessenger, TransactionCommand, TransactionEvent}

trait TransactionState extends JsonSerializable {
  def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState

  def proceed()(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit

  def handleCommand(command: TransactionCommand)(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState]
}
