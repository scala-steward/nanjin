package com.github.chenharryhua.nanjin.pipes

import cats.ApplicativeError
import com.sksamuel.avro4s.{ToRecord, Decoder as AvroDecoder, Encoder as AvroEncoder}
import fs2.{Pipe, Stream}
import org.apache.avro.generic.GenericRecord

final class GenericRecordCodec[F[_], A] extends Serializable {

  def encode(enc: AvroEncoder[A])(implicit F: ApplicativeError[F, Throwable]): Pipe[F, A, GenericRecord] = {
    val toRec: ToRecord[A] = ToRecord(enc)
    (ss: Stream[F, A]) => ss.map(toRec.to)
  }

  def decode(dec: AvroDecoder[A]): Pipe[F, GenericRecord, A] =
    (ss: Stream[F, GenericRecord]) => ss.map(rec => dec.decode(rec))

}
