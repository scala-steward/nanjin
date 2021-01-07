package com.github.chenharryhua.nanjin.devices

import akka.stream.alpakka.ftp.scaladsl.{Ftp, FtpApi, Ftps, Sftp}
import akka.stream.alpakka.ftp.{FtpSettings, FtpsSettings, RemoteFileSettings, SftpSettings}
import akka.stream.{IOResult, Materializer}
import akka.util.ByteString
import cats.effect.{ConcurrentEffect, ContextShift}
import fs2.{Pipe, Stream}
import net.schmizz.sshj.SSHClient
import org.apache.commons.net.ftp.{FTPClient, FTPSClient}
import streamz.converter._

sealed abstract class FtpUploader[F[_], C, S <: RemoteFileSettings](ftpApi: FtpApi[C, S], settings: S) {

  final def upload(pathStr: String)(implicit
    F: ConcurrentEffect[F],
    cs: ContextShift[F],
    mat: Materializer): Pipe[F, Byte, IOResult] = { (ss: Stream[F, Byte]) =>
    Stream
      .eval(ftpApi.toPath(pathStr, settings).toPipeMatWithResult[F](F, cs, mat.executionContext, mat))
      .flatMap(p => ss.chunks.through(p.compose(_.map(bs => ByteString(bs.toArray)))).rethrow)
  }
}

final class AkkaFtpUploader[F[_]](settings: FtpSettings) extends FtpUploader[F, FTPClient, FtpSettings](Ftp, settings)

final class AkkaSftpUploader[F[_]](settings: SftpSettings)
    extends FtpUploader[F, SSHClient, SftpSettings](Sftp, settings)

final class AkkaFtpsUploader[F[_]](settings: FtpsSettings)
    extends FtpUploader[F, FTPSClient, FtpsSettings](Ftps, settings)
