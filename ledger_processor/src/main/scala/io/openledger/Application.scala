package io.openledger

import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.kafka.{CommitterSettings, ConsumerSettings, ProducerSettings}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.stream.{QueueCompletionResult, QueueOfferResult}
import io.openledger.api.kafka.StreamConsumer
import io.openledger.api.kafka.StreamConsumer.StreamOp
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account._
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{apply => _, _}
import io.openledger.setup.{KafkaConsumerSetup, KafkaProducerSetup}
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer, StringDeserializer, StringSerializer}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{DurationInt, FiniteDuration, MILLISECONDS}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.language.implicitConversions
import scala.util.{Failure, Success}

object Application extends App {
  private final val logger = LoggerFactory.getLogger(this.getClass)

  implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(Behaviors.setup[SpawnProtocol.Command] { context =>
    SpawnProtocol()
  }, "openledger")
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  AkkaManagement(system).start()
  ClusterBootstrap(system).start()
  val coordinatedShutdown = CoordinatedShutdown(system)
  val sharding = ClusterSharding(system)

  val accountShardRegion: ActorRef[ShardingEnvelope[Account.AccountCommand]] =
    sharding.init(Entity(AccountTypeKey)(createBehavior = entityContext => Account(entityContext.entityId)(transactionMessenger, () => DateUtils.now())))
  val transactionShardRegion: ActorRef[ShardingEnvelope[Transaction.TransactionCommand]] =
    sharding.init(Entity(TransactionTypeKey)(createBehavior = entityContext => Transaction(entityContext.entityId)(accountMessenger, resultMessenger)))
  val producerQueue = KafkaProducerSetup(producerSettings, coordinatedShutdown).run()
  private val producerSettings = KafkaProducerSetup.KafkaProducerSettings(
    topic = system.settings.config.getString("ledger-settings.kafka.outgoing.topic"),
    bufferSize = system.settings.config.getInt("ledger-settings.kafka.outgoing.buffer-size"),
    kafkaProducerSettings = ProducerSettings(
      config = system.settings.config.getConfig("akka.kafka.producer"),
      keySerializer = new StringSerializer,
      valueSerializer = new ByteArraySerializer
    )
  )
  private val streamConsumerSettings = KafkaConsumerSetup.KafkaConsumerSettings(
    processingTimeout = FiniteDuration(system.settings.config.getDuration("ledger-settings.processor.timeout").toMillis, MILLISECONDS),
    topics = system.settings.config.getStringList("ledger-settings.kafka.incoming.topics").asScala.toSet,
    messagePerSecond = system.settings.config.getInt("ledger-settings.kafka.incoming.message-per-second"),
    kafkaSourceSettings = ConsumerSettings(
      config = system.settings.config.getConfig("akka.kafka.consumer"),
      keyDeserializer = new StringDeserializer,
      valueDeserializer = new ByteArrayDeserializer),
    kafkaComitterSettings = CommitterSettings(
      config = system.settings.config.getConfig("akka.kafka.committer"))
  )

  def transactionMessenger(transactionId: String, message: AccountingStatus): Unit = message match {
    case AccountingSuccessful(cmdHash, accountId, availableBalance, currentBalance, _, timestamp) =>
      transactionResolver(transactionId) ! AcceptAccounting(cmdHash, accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
    case AccountingFailed(cmdHash, accountId, code) =>
      transactionResolver(transactionId) ! RejectAccounting(cmdHash, accountId, code)
  }

  def transactionResolver(transactionId: String): RecipientRef[TransactionCommand] = sharding.entityRefFor(TransactionTypeKey, transactionId)

  def accountMessenger(accountId: String, message: AccountingCommand): Unit = {
    accountResolver(accountId) ! message
  }

  def accountResolver(accountId: String): RecipientRef[AccountCommand] = sharding.entityRefFor(AccountTypeKey, accountId)

  def resultMessenger(message: TransactionResult): Unit = {
    producerQueue.offer(message).onComplete {
      case Success(value) => value match {
        case r: QueueCompletionResult => r match {
          case QueueOfferResult.Failure(cause) =>
            logger.error(s"ALERT: $message queued after stream has failed", cause)
          case QueueOfferResult.QueueClosed =>
            logger.error(s"ALERT: $message queued after stream has closed")
        }
        case QueueOfferResult.Enqueued =>
          logger.info(s"$message enqueued to outgoing stream")
        case QueueOfferResult.Dropped =>
          logger.error(s"ALERT: $message dropped from outgoing stream")
      }
      case Failure(exception) =>
        logger.error(s"ALERT: enqueueing $message failed", exception)
    }
  }

  for (
    consumerActor <- system.ask((replyTo: ActorRef[ActorRef[StreamOp]]) => SpawnProtocol.Spawn(StreamConsumer((id) => sharding.entityRefFor(TransactionTypeKey, id), resultMessenger)(streamConsumerSettings.processingTimeout, scheduler, executionContext), name = "StreamConsumer", props = Props.empty, replyTo))(10.seconds, scheduler)
  ) yield KafkaConsumerSetup(streamConsumerSettings, coordinatedShutdown, consumerActor).run()

}
