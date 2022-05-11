package com.github.chenharryhua.nanjin.aws

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all.*
import com.amazonaws.services.simpleemail.model.*
import com.amazonaws.services.simpleemail.{AmazonSimpleEmailService, AmazonSimpleEmailServiceClientBuilder}
import io.circe.generic.JsonCodec
import io.circe.syntax.EncoderOps
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

@JsonCodec
final case class EmailContent(from: String, to: List[String], subject: String, body: String)

sealed trait SimpleEmailService[F[_]] {
  def send(txt: EmailContent): F[SendEmailResult]
  def updateBuilder(
    f: AmazonSimpleEmailServiceClientBuilder => AmazonSimpleEmailServiceClientBuilder): SimpleEmailService[F]
}

object SimpleEmailService {

  private val name: String = "aws.SES"

  def apply[F[_]: Sync](f: AmazonSimpleEmailServiceClientBuilder => AmazonSimpleEmailServiceClientBuilder)
    : Resource[F, SimpleEmailService[F]] =
    for {
      logger <- Resource.eval(Slf4jLogger.create[F])
      er <- Resource.makeCase(logger.info(s"initialize $name").map(_ => new AwsSES[F](f, logger))) {
        case (cw, quitCase) => cw.shutdown(name, quitCase, logger)
      }
    } yield er

  def fake[F[_]](implicit F: Sync[F]): Resource[F, SimpleEmailService[F]] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    Resource.make(F.pure(new SimpleEmailService[F] {
      override def send(txt: EmailContent): F[SendEmailResult] =
        logger.info(txt.asJson.noSpaces) *> F.pure(new SendEmailResult)

      override def updateBuilder(
        f: AmazonSimpleEmailServiceClientBuilder => AmazonSimpleEmailServiceClientBuilder): SimpleEmailService[F] = this
    }))(_ => F.unit)
  }

  final private class AwsSES[F[_]](
    buildFrom: AmazonSimpleEmailServiceClientBuilder => AmazonSimpleEmailServiceClientBuilder,
    logger: Logger[F])(implicit F: Sync[F])
      extends ShutdownService[F] with SimpleEmailService[F] {

    private lazy val client: AmazonSimpleEmailService =
      buildFrom(AmazonSimpleEmailServiceClientBuilder.standard()).build()

    override def send(content: EmailContent): F[SendEmailResult] = {
      val request = new SendEmailRequest()
        .withDestination(new Destination().withToAddresses(content.to*))
        .withMessage(
          new Message()
            .withBody(new Body().withHtml(new Content().withCharset("UTF-8").withData(content.body)))
            .withSubject(new Content().withCharset("UTF-8").withData(content.subject)))
        .withSource(content.from)
      F.blocking(client.sendEmail(request)).attempt.flatMap(r => r.swap.traverse(logger.error(_)(name)).as(r)).rethrow
    }

    override protected val closeService: F[Unit] = F.blocking(client.shutdown())

    override def updateBuilder(
      f: AmazonSimpleEmailServiceClientBuilder => AmazonSimpleEmailServiceClientBuilder): SimpleEmailService[F] =
      new AwsSES[F](buildFrom.andThen(f), logger)
  }
}
