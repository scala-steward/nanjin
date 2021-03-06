package mtest

import java.io.PrintWriter
import java.net.InetAddress

import akka.actor.ActorSystem
import akka.stream.alpakka.ftp.FtpCredentials.NonAnonFtpCredentials
import akka.stream.alpakka.ftp.{FtpCredentials, FtpSettings}
import cats.effect.IO
import org.apache.commons.net.PrintCommandListener
import org.apache.commons.net.ftp.FTPClient

import scala.concurrent.ExecutionContext.Implicits.global
import cats.effect.Temporal

package object terminals {

  val akkaSystem: ActorSystem = ActorSystem("nj-devices")

  val cred = FtpCredentials.create("chenh", "test")

  val ftpSettins =
    FtpSettings(InetAddress.getLocalHost)
      .withPort(21)
      .withCredentials(cred)
      .withPassiveMode(true)
      .withConfigureConnection { (ftpClient: FTPClient) =>
        ftpClient.addProtocolCommandListener(new PrintCommandListener(new PrintWriter(System.out), true))
        ftpClient.setRemoteVerificationEnabled(false)
      }
}
