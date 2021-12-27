package com.github.chenharryhua.nanjin.aws

import cats.Applicative
import cats.effect.kernel.Resource.ExitCase
import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.all.*
import com.amazonaws.regions.Regions
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersRequest
import com.amazonaws.services.simplesystemsmanagement.{
  AWSSimpleSystemsManagement,
  AWSSimpleSystemsManagementClientBuilder
}
import com.github.chenharryhua.nanjin.common.aws.{ParameterStoreContent, ParameterStorePath}
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.Base64

sealed trait ParameterStore[F[_]] {
  def fetch(path: ParameterStorePath): F[ParameterStoreContent]

  final def base64(path: ParameterStorePath)(implicit F: Applicative[F]): F[Array[Byte]] =
    fetch(path).map(c => Base64.getDecoder.decode(c.value.getBytes))
}

object ParameterStore {
  private val name: String = "aws.ParameterStore"

  def fake[F[_]](content: String)(implicit F: Applicative[F]): Resource[F, ParameterStore[F]] =
    Resource.make(F.pure(new ParameterStore[F] {

      override def fetch(path: ParameterStorePath): F[ParameterStoreContent] =
        ParameterStoreContent(content).pure

    }))(_ => F.unit)

  def apply[F[_]](regions: Regions)(implicit F: Sync[F]): Resource[F, ParameterStore[F]] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    Resource.makeCase(logger.info(s"initialize $name").map(_ => new AwsPS(regions, logger))) { case (cw, quitCase) =>
      val logging = quitCase match {
        case ExitCase.Succeeded  => logger.info(s"$name was closed normally")
        case ExitCase.Errored(e) => logger.warn(e)(s"$name was closed abnormally")
        case ExitCase.Canceled   => logger.info(s"$name was canceled")
      }
      logging *> cw.shutdown
    }
  }

  def apply[F[_]: Async]: Resource[F, ParameterStore[F]] = apply[F](defaultRegion)

  final private class AwsPS[F[_]](regions: Regions, logger: Logger[F])(implicit F: Sync[F])
      extends ParameterStore[F] with ShutdownService[F] {
    private val client: AWSSimpleSystemsManagement =
      AWSSimpleSystemsManagementClientBuilder.standard.withRegion(regions).build

    override def fetch(path: ParameterStorePath): F[ParameterStoreContent] = {
      val req = new GetParametersRequest().withNames(path.value).withWithDecryption(path.isSecure)
      F.blocking(ParameterStoreContent(client.getParameters(req).getParameters.get(0).getValue))
        .attempt
        .flatMap(r => r.swap.traverse(logger.error(_)(name)).as(r))
        .rethrow
    }

    override def shutdown: F[Unit] = F.blocking(client.shutdown())
  }
}
