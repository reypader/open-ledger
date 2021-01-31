package io.openledger.transaction

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.account.Account._
import io.openledger.transaction.Transaction.{apply => _, _}
import io.openledger.transaction.states._
import io.openledger.{DateUtils, LedgerError}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Duration
import java.util.UUID
import scala.language.postfixOps

class TransactionSpec
  extends ScalaTestWithActorTestKit(config = ConfigFactory.parseString(
    """
    akka.actor.serialization-bindings {
        "io.openledger.JsonSerializable" = jackson-json
    }
    """).withFallback(EventSourcedBehaviorTestKit.config))
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with MockFactory {

  private val stubAccountMessenger = mockFunction[String, AccountingCommand, Unit]
  private val stubResultMessenger = mockFunction[TransactionResult, Unit]
  private val txnId = UUID.randomUUID().toString
  private val accountIdToDebit = UUID.randomUUID().toString
  private val accountIdToCredit = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[TransactionCommand, TransactionEvent, TransactionState](system, Transaction(txnId)(stubAccountMessenger, stubResultMessenger))
  private val transactionAmount = BigDecimal(1)
  private val theTime = DateUtils.now()

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Transaction" when {
    "Ready" must {
      "transition to Debiting after Started" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once
        }
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount
      }
    }

    "Debiting" must {
      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount
      }

      "transition to Crediting after DebitSucceeded" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime
      }

      "transition to Failed after DebitHoldFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        debitResult.events shouldBe Seq(DebitHoldFailed(LedgerError.INSUFFICIENT_BALANCE))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        debitResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Failed].transactionId shouldBe txnId
        debitResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }

    "Crediting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, holdTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime
      }

      "transition to Posting after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, transactionAmount, BigDecimal(0), holdTime)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amount shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, transactionAmount)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
      }
    }

    "RollingBackDebit due to Error" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
      }

      "transition to Failed after CreditAdjustmentDone (Release)" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        creditResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Failed].transactionId shouldBe txnId
        creditResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }
    "Posting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, holdTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amount shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime
      }

      "transition to Posted after DebitPostSucceeded" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, transactionAmount, BigDecimal(0), holdTime)) once

          stubResultMessenger expects TransactionSuccessful(debitPostedAccountResultingBalance, expectedCreditResultingBalance) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, debitPostedAccountResultingBalance, theTime))
        creditResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amount shouldBe transactionAmount
        creditResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount
      }

      "transition to RollingBackCredit after DebitPostFailed" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, transactionAmount, BigDecimal(0), holdTime)) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount))  once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(DebitPostFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackCredit].amountCaptured shouldBe None
        creditResult.stateOfType[RollingBackCredit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
      }
    }
    "Posted" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amount shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitPostedBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amount shouldBe transactionAmount
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount
      }

      "transition to RollingBackCredit on ReversalRequested" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None
      }
    }

    "RollingBackCredit" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amount shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitPostedBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amount shouldBe transactionAmount
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None
      }

      "transition to RollingBackDebit on DebitAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        reverseResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        reverseResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)
      }
    }

    "RollingBackDebit due to Reversal" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amount shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitPostedBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amount shouldBe transactionAmount
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None

        val reverseCreditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        reverseCreditResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        reverseCreditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseCreditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseCreditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        reverseCreditResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
        reverseCreditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)
      }

      "transition to Reversed after CreditAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionReversed() once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Reversed].transactionId shouldBe txnId
      }
    }
  }

}