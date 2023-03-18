package com.github.chenharryhua.nanjin.aws

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import cats.{Endo, Show}
import com.github.chenharryhua.nanjin.common.aws.{S3Path, SqsConfig}
import fs2.{Chunk, Pull, Stream}
import io.circe.Json
import io.circe.generic.JsonCodec
import io.circe.optics.JsonPath.*
import io.circe.parser.*
import io.circe.syntax.EncoderOps
import monocle.macros.Lenses
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import retry.{PolicyDecision, RetryPolicies, RetryPolicy as DelayPolicy, RetryStatus}
import software.amazon.awssdk.services.sqs.model.*
import software.amazon.awssdk.services.sqs.{SqsClient, SqsClientBuilder}

import java.net.URLDecoder
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.ListHasAsScala

/** @param messageIndex
  *   one based message index
  * @param batchSize
  *   number of messages in one request
  */

final case class SqsMessage(
  request: ReceiveMessageRequest,
  response: Message,
  batchIndex: Long,
  messageIndex: Int,
  batchSize: Int) {
  def asJson: Json =
    Json.obj(
      "batchIndex" -> batchIndex.asJson,
      "messageIndex" -> messageIndex.asJson,
      "batchSize" -> batchSize.asJson)
}

sealed trait SimpleQueueService[F[_]] {
  def receive(request: ReceiveMessageRequest): Stream[F, SqsMessage]
  final def receive(f: Endo[ReceiveMessageRequest.Builder]): Stream[F, SqsMessage] =
    receive(f(ReceiveMessageRequest.builder()).build())

  final def receive(sqs: SqsConfig): Stream[F, SqsMessage] =
    receive(
      ReceiveMessageRequest
        .builder()
        .queueUrl(sqs.queueUrl)
        .waitTimeSeconds(sqs.waitTimeSeconds.value)
        .maxNumberOfMessages(sqs.maxNumberOfMessages.value)
        .visibilityTimeout(sqs.visibilityTimeout.value)
        .build())

  def delete(msg: SqsMessage): F[DeleteMessageResponse]
  def sendMessage(msg: SendMessageRequest): F[SendMessageResponse]
  def resetVisibility(msg: SqsMessage): F[ChangeMessageVisibilityResponse]

  def updateBuilder(f: Endo[SqsClientBuilder]): SimpleQueueService[F]
  def withDelayPolicy(delayPolicy: DelayPolicy[F]): SimpleQueueService[F]
}

object SimpleQueueService {

  private val name: String = "aws.SQS"

  def fake[F[_]](duration: FiniteDuration, body: String)(implicit
    F: Async[F]): Resource[F, SimpleQueueService[F]] =
    Resource.make(F.pure(new SimpleQueueService[F] {
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
      override def receive(request: ReceiveMessageRequest): Stream[F, SqsMessage] =
        Stream.fixedRate(duration).zipWithIndex.map { case (_, idx) =>
          SqsMessage(
            request = request,
            response = Message
              .builder()
              .messageId(UUID.randomUUID().show)
              .body(body)
              .receiptHandle(idx.toString)
              .build(),
            batchIndex = idx,
            messageIndex = 1,
            batchSize = 1
          )
        }

      override def updateBuilder(f: Endo[SqsClientBuilder]): SimpleQueueService[F]     = this
      override def withDelayPolicy(delayPolicy: DelayPolicy[F]): SimpleQueueService[F] = this
      override def delete(msg: SqsMessage): F[DeleteMessageResponse] =
        F.pure(DeleteMessageResponse.builder().build())
      override def sendMessage(msg: SendMessageRequest): F[SendMessageResponse] =
        logger
          .info(msg.messageBody())
          .map(_ =>
            SendMessageResponse
              .builder()
              .messageId("fake.message.id")
              .sequenceNumber("fake.sequence.number")
              .build())

      override def resetVisibility(msg: SqsMessage): F[ChangeMessageVisibilityResponse] =
        F.pure(ChangeMessageVisibilityResponse.builder().build())
    }))(_ => F.unit)

  def apply[F[_]: Async](f: Endo[SqsClientBuilder]): Resource[F, SimpleQueueService[F]] = {
    val defaultPolicy: DelayPolicy[F] =
      RetryPolicies.capDelay(5.minutes, RetryPolicies.exponentialBackoff(10.seconds))
    for {
      logger <- Resource.eval(Slf4jLogger.create[F])
      sqs <- Resource.makeCase(
        logger.info(s"initialize $name").map(_ => new AwsSQS[F](f, defaultPolicy, logger))) {
        case (cw, quitCase) =>
          cw.shutdown(name, quitCase, logger)
      }
    } yield sqs
  }

  final private class AwsSQS[F[_]](
    buildFrom: Endo[SqsClientBuilder],
    delayPolicy: DelayPolicy[F],
    logger: Logger[F])(implicit F: Async[F])
      extends ShutdownService[F] with SimpleQueueService[F] {

    private lazy val client: SqsClient = buildFrom(SqsClient.builder()).build()

    override protected val closeService: F[Unit] = F.blocking(client.close())

    override def receive(request: ReceiveMessageRequest): Stream[F, SqsMessage] = {

      // when no data can be retrieved, the delay policy will be applied
      // [[https://cb372.github.io/cats-retry/docs/policies.html]]
      def receiving(status: RetryStatus, batchIndex: Long): Pull[F, SqsMessage, Unit] =
        Pull.eval(F.blocking(client.receiveMessage(request)).onError(ex => logger.error(ex)(name))).flatMap {
          rmr =>
            val messages: mutable.Buffer[Message] = rmr.messages.asScala
            val size: Int                         = messages.size
            if (size > 0) {
              val chunk: Chunk[SqsMessage] = Chunk.iterable(messages).zipWithIndex.map { case (msg, idx) =>
                SqsMessage(
                  request = request,
                  response = msg,
                  batchIndex = batchIndex,
                  messageIndex = idx + 1, // one based index in a batch
                  batchSize = size
                )
              }
              Pull.output(chunk) >> receiving(RetryStatus.NoRetriesYet, batchIndex + 1)
            } else {
              Pull.eval(delayPolicy.decideNextRetry(status)).flatMap {
                case PolicyDecision.GiveUp => Pull.done
                case PolicyDecision.DelayAndRetry(delay) =>
                  Pull.sleep(delay) >> receiving(status.addRetry(delay), batchIndex)
              }
            }
        }

      receiving(RetryStatus.NoRetriesYet, 0L).stream
    }

    override def delete(msg: SqsMessage): F[DeleteMessageResponse] = {
      val request = DeleteMessageRequest
        .builder()
        .queueUrl(msg.request.queueUrl)
        .receiptHandle(msg.response.receiptHandle)
        .build()
      F.blocking(client.deleteMessage(request)).onError(ex => logger.error(ex)(request.toString))
    }

    override def sendMessage(request: SendMessageRequest): F[SendMessageResponse] =
      F.blocking(client.sendMessage(request)).onError(ex => logger.error(ex)(request.toString))

    override def resetVisibility(msg: SqsMessage): F[ChangeMessageVisibilityResponse] = {
      val request: ChangeMessageVisibilityRequest =
        ChangeMessageVisibilityRequest.builder
          .queueUrl(msg.request.queueUrl)
          .receiptHandle(msg.response.receiptHandle)
          .visibilityTimeout(0)
          .build()
      F.blocking(client.changeMessageVisibility(request)).onError(ex => logger.error(ex)(request.toString))
    }

    override def updateBuilder(f: Endo[SqsClientBuilder]): SimpleQueueService[F] =
      new AwsSQS[F](buildFrom.andThen(f), delayPolicy, logger)

    override def withDelayPolicy(delayPolicy: DelayPolicy[F]): SimpleQueueService[F] =
      new AwsSQS[F](buildFrom, delayPolicy, logger)

  }
}

object sqsS3Parser {
  @JsonCodec @Lenses
  final case class SqsS3File(path: S3Path, size: Long)
  object SqsS3File {
    implicit val showSqsS3File: Show[SqsS3File] = cats.derived.semiauto.show[SqsS3File]
  }

  /** [[https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-content-structure.html]]
    *
    * ignore messages which do not have s3 structure
    */
  def apply(msg: SqsMessage): List[SqsS3File] =
    Option(msg.response)
      .flatMap(m => parse(m.body()).toOption)
      .traverse { json =>
        root.Records.each.s3.json.getAll(json).flatMap { js =>
          val bucket = js.hcursor.downField("bucket").get[String]("name")
          val key    = js.hcursor.downField("object").get[String]("key")
          val size   = js.hcursor.downField("object").get[Long]("size")
          (bucket, key, size)
            .mapN((b, k, s) => SqsS3File(S3Path(b, URLDecoder.decode(k, "UTF-8")), s))
            .toOption
        }
      }
      .flatten
}
