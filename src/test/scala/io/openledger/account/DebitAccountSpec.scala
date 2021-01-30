package io.openledger.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.LedgerError
import io.openledger.account.Account._
import io.openledger.account.states.{AccountState, DebitAccount}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.language.postfixOps

class DebitAccountSpec
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

  private val stubMessenger = mockFunction[String, AccountingStatus, Unit]
  private val txnId = UUID.randomUUID().toString
  private val accountId = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(accountId)(stubMessenger))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
    eventSourcedTestKit.runCommand(Open(AccountMode.DEBIT))
  }

  "A Debit Account" when {

    "at initially 0/0/0 balance" must {
      def given(): Unit = {}

      "reject Credit(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Credit(txnId, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Debit(uuid, 1) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0)) once
        val result = eventSourcedTestKit.runCommand(Debit(txnId, 1))
        result.events shouldBe Seq(Debited(txnId, 1, 1))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Hold(uuid, 1) and have 1/0/1 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 0, 1)) once
        val result = eventSourcedTestKit.runCommand(Hold(txnId, 1))
        result.events shouldBe Seq(Authorized(txnId, 1, 1))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Capture(txnId, 1, 0))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have -1/-1/0 balance with Overpaid" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, -1, -1, 0)) once
        val result = eventSourcedTestKit.runCommand(CreditAdjust(txnId, 1))
        result.events shouldBe Seq(Credited(txnId, -1, -1), Overpaid(txnId))
        result.stateOfType[DebitAccount].availableBalance shouldBe -1
        result.stateOfType[DebitAccount].currentBalance shouldBe -1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0)) once
        val result = eventSourcedTestKit.runCommand(DebitAdjust(txnId, 1))
        result.events shouldBe Seq(Debited(txnId, 1, 1))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }
    }

    "at initially 1/1/0 balance" must {
      def given(): Unit = {
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0)) once
        val given = eventSourcedTestKit.runCommand(Debit(txnId, 1))
      }

      "accept Credit(uuid, 1) and have 0/0/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, 0, 0)) once
        val result = eventSourcedTestKit.runCommand(Credit(txnId, 1))
        result.events shouldBe Seq(Credited(txnId, 0, 0))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Debit(uuid, 1) and have 2/2/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 2, 0)) once
        val result = eventSourcedTestKit.runCommand(Debit(txnId, 1))
        result.events shouldBe Seq(Debited(txnId, 2, 2))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 2
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Hold(uuid, 1) and have 2/1/1 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 1, 1)) once
        val result = eventSourcedTestKit.runCommand(Hold(txnId, 1))
        result.events shouldBe Seq(Authorized(txnId, 2, 1))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Capture(txnId, 1, 0))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 0/0/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, 0, 0)) once
        val result = eventSourcedTestKit.runCommand(CreditAdjust(txnId, 1))
        result.events shouldBe Seq(Credited(txnId, 0, 0))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 2/2/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 2, 0)) once
        val result = eventSourcedTestKit.runCommand(DebitAdjust(txnId, 1))
        result.events shouldBe Seq(Debited(txnId, 2, 2))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 2
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

    }

    "at initially 1/0/1 balance" must {
      def given(): Unit = {
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 0, 1)) once
        val given2 = eventSourcedTestKit.runCommand(Hold(txnId, 1))
      }

      "accept Credit(uuid, 1) and have 0/-1/1 balance with Overpaid" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, -1, 1)) once
        val result = eventSourcedTestKit.runCommand(Credit(txnId, 1))
        result.events shouldBe Seq(Credited(txnId, 0, -1), Overpaid(txnId))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe -1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Debit(uuid, 1) and have 2/1/1 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 1, 1)) once
        val result = eventSourcedTestKit.runCommand(Debit(txnId, 1))
        result.events shouldBe Seq(Debited(txnId, 2, 1))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Hold(uuid, 1) and have 2/0/2 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 0, 2)) once
        val result = eventSourcedTestKit.runCommand(Hold(txnId, 1))
        result.events shouldBe Seq(Authorized(txnId, 2, 2))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 2
      }

      "accept Capture(uuid, 1,0) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0)) once
        val result = eventSourcedTestKit.runCommand(Capture(txnId, 1, 0))
        result.events shouldBe Seq(Captured(txnId, 1, 1, 0))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Capture(uuid, 1,1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Capture(txnId, 1, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 2,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Capture(txnId, 2, 0))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Release(uuid, 1) and have 0/0/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, 0, 0)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, 1))
        result.events shouldBe Seq(Released(txnId, 0, 0))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 2) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, 2))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

    }

    "at initially 2/0/2 balance" must {
      def given(): Unit = {
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 0, 2)) once
        val given2 = eventSourcedTestKit.runCommand(Hold(txnId, 2))
      }

      "accept Capture(uuid, 1,1) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0)) once
        val result = eventSourcedTestKit.runCommand(Capture(txnId, 1, 1))
        result.events shouldBe Seq(Captured(txnId, 1, 1, 0))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1), Capture(uuid, 2,0) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, -1, 2)) once
        val result1 = eventSourcedTestKit.runCommand(Credit(txnId, 1))
        result1.events shouldBe Seq(Credited(txnId, 1, -1), Overpaid(txnId))
        result1.stateOfType[DebitAccount].availableBalance shouldBe 1
        result1.stateOfType[DebitAccount].currentBalance shouldBe -1
        result1.stateOfType[DebitAccount].authorizedBalance shouldBe 2

        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0)) once
        val result2 = eventSourcedTestKit.runCommand(Capture(txnId, 2, 0))
        result2.events shouldBe Seq(Captured(txnId, 1, 1, 0))
        result2.stateOfType[DebitAccount].availableBalance shouldBe 1
        result2.stateOfType[DebitAccount].currentBalance shouldBe 1
        result2.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 2), Capture(uuid, 1,1) and have -1/-1/0 balance with Overpaid" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, -2, 2)) once
        val result1 = eventSourcedTestKit.runCommand(CreditAdjust(txnId, 2))
        result1.events shouldBe Seq(Credited(txnId, 0, -2), Overpaid(txnId))
        result1.stateOfType[DebitAccount].availableBalance shouldBe 0
        result1.stateOfType[DebitAccount].currentBalance shouldBe -2
        result1.stateOfType[DebitAccount].authorizedBalance shouldBe 2

        stubMessenger expects(txnId, AccountingSuccessful(accountId, -1, -1, 0)) once
        val result2 = eventSourcedTestKit.runCommand(Capture(txnId, 1, 1))
        result2.events shouldBe Seq(Captured(txnId, -1, -1, 0), Overpaid(txnId))
        result2.stateOfType[DebitAccount].availableBalance shouldBe -1
        result2.stateOfType[DebitAccount].currentBalance shouldBe -1
        result2.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }
    }
  }
}