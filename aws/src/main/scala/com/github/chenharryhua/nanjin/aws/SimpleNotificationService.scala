package com.github.chenharryhua.nanjin.aws

import cats.effect.kernel.Resource.ExitCase
import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all.*
import com.amazonaws.regions.Regions
import com.amazonaws.services.sns.model.{PublishRequest, PublishResult}
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import com.github.chenharryhua.nanjin.common.aws.SnsArn
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed trait SimpleNotificationService[F[_]] {
  def publish(msg: => String): F[PublishResult]
}

object SimpleNotificationService {

  def fake[F[_]](implicit F: Sync[F]): Resource[F, SimpleNotificationService[F]] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    Resource.make(F.pure(new SimpleNotificationService[F] {
      override def publish(msg: => String): F[PublishResult] =
        logger.info(msg) *> F.pure(new PublishResult())
    }))(_ => F.unit)
  }

  def apply[F[_]](topic: SnsArn, region: Regions)(implicit F: Sync[F]): Resource[F, SimpleNotificationService[F]] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    Resource.makeCase(F.delay(new SNS[F](topic, region))) { case (cw, quitCase) =>
      val logging = quitCase match {
        case ExitCase.Succeeded  => logger.info("NJ.SNS was closed normally")
        case ExitCase.Errored(e) => logger.warn(e)("NJ.SNS was closed abnormally")
        case ExitCase.Canceled   => logger.info("NJ.SNS was canceled")
      }
      logging *> cw.shutdown
    }
  }

  def apply[F[_]: Sync](topic: SnsArn): Resource[F, SimpleNotificationService[F]] = apply[F](topic, defaultRegion)

  final private class SNS[F[_]](topic: SnsArn, region: Regions)(implicit F: Sync[F])
      extends SimpleNotificationService[F] with ShutdownService[F] {
    private val snsClient: AmazonSNS = AmazonSNSClientBuilder.standard().withRegion(region).build()

    override def publish(msg: => String): F[PublishResult] =
      F.blocking(snsClient.publish(new PublishRequest(topic.value, msg)))

    override def shutdown: F[Unit] = F.blocking(snsClient.shutdown())
  }
}
