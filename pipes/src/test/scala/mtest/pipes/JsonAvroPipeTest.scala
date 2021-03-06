package mtest.pipes

import cats.effect.IO
import com.github.chenharryhua.nanjin.pipes.{GenericRecordCodec, JacksonSerialization}
import com.sksamuel.avro4s.AvroSchema
import fs2.Stream
import org.scalatest.funsuite.AnyFunSuite
import cats.effect.unsafe.implicits.global

class JsonAvroPipeTest extends AnyFunSuite {
  import TestData._
  val gser = new GenericRecordCodec[IO, Tigger]
  val ser  = new JacksonSerialization[IO](AvroSchema[Tigger])

  test("json-avro identity") {
    val data: Stream[IO, Tigger] = Stream.emits(tiggers)

    assert(
      data
        .through(gser.encode(Tigger.avroEncoder))
        .through(ser.serialize)
        .through(ser.deserialize)
        .through(gser.decode(Tigger.avroDecoder))
        .compile
        .toList
        .unsafeRunSync() === tiggers)
  }
  test("jackson-compact-string size") {
    val data: Stream[IO, Tigger] = Stream.emits(tiggers)

    assert(
      data.through(gser.encode(Tigger.avroEncoder)).through(ser.compactJson).compile.toList.unsafeRunSync().size == 10)
  }
  test("jackson-pretty-string size") {
    val data: Stream[IO, Tigger] = Stream.emits(tiggers)

    assert(
      data.through(gser.encode(Tigger.avroEncoder)).through(ser.prettyJson).compile.toList.unsafeRunSync().size == 10)
  }

}
