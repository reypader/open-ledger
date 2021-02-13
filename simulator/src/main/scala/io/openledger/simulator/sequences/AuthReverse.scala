package io.openledger.simulator.sequences

import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations._

import java.util.UUID

case class AuthReverse(participants: Seq[String]) extends SequenceGenerator {

  private val authCap: Map[String, Seq[EntryRequest]] = {

    createPairs(participants).flatMap(pair => {
      val txnId = UUID.randomUUID().toString
      Seq(
        EntryRequest(
          Operation.Authorize(
            Authorize(
              entryCode = "AUTH_REV",
              entryId = txnId,
              accountToDebit = pair._1,
              accountToCredit = pair._2,
              amount = 1
            )
          )
        )
      )
    })
  }.groupBy(r =>
    r.operation match {
      case Operation.Authorize(_) => "AUTH"
      case _                      => throw new IllegalArgumentException()
    }
  )

  private val entries = reverse(authCap("AUTH"))

  override def generate(): Seq[EntryRequest] = entries
  override def count(): Int = entries.size
  override def toString: String =
    s"${entries.count(r => r.operation.isAuthorize)} auths rotated among ${participants.size} accounts followed by ${entries
      .count(r => r.operation.isReverse)} reversals"

}