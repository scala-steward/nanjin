package com.github.chenharryhua.nanjin.pipes

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Framing}
import akka.util.ByteString
import com.github.chenharryhua.nanjin.terminals.NEWLINE_SEPERATOR
import fs2.text.{lines, utf8}
import fs2.{Pipe, RaiseThrowable, Stream}
import io.circe.parser.decode
import io.circe.{Decoder as JsonDecoder, Encoder as JsonEncoder, Json}

object CirceSerde {

  @inline private def encoder[A](isKeepNull: Boolean, enc: JsonEncoder[A]): A => Json =
    (a: A) => if (isKeepNull) enc(a) else enc(a).deepDropNullValues

  def toBytes[F[_], A](isKeepNull: Boolean)(implicit enc: JsonEncoder[A]): Pipe[F, A, Byte] = {
    val encode = encoder[A](isKeepNull, enc)
    (ss: Stream[F, A]) => ss.mapChunks(_.map(encode(_).noSpaces)).intersperse(NEWLINE_SEPERATOR).through(utf8.encode)
  }

  def fromBytes[F[_], A](implicit ev: RaiseThrowable[F], dec: JsonDecoder[A]): Pipe[F, Byte, A] =
    (ss: Stream[F, Byte]) =>
      ss.through(utf8.decode).through(lines).filter(_.nonEmpty).mapChunks(_.map(decode[A])).rethrow

  object akka {
    def toByteString[A](isKeepNull: Boolean)(implicit enc: JsonEncoder[A]): Flow[A, ByteString, NotUsed] = {
      val encode = encoder[A](isKeepNull, enc)
      Flow[A].map(encode).map(js => ByteString.fromString(js.noSpaces)).intersperse(ByteString(NEWLINE_SEPERATOR))
    }

    def fromByteString[A](implicit dec: JsonDecoder[A]): Flow[ByteString, A, NotUsed] =
      Flow[ByteString]
        .via(Framing.delimiter(ByteString(NEWLINE_SEPERATOR), Int.MaxValue, allowTruncation = true))
        .map(_.utf8String)
        .map(s =>
          decode(s) match {
            case Left(ex)     => throw ex
            case Right(value) => value
          })
  }
}