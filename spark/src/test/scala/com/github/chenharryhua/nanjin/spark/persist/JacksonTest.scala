package com.github.chenharryhua.nanjin.spark.persist

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.pipes.JacksonSerde
import com.github.chenharryhua.nanjin.spark.SparkSessionExt
import com.github.chenharryhua.nanjin.terminals.NJPath
import eu.timepit.refined.auto.*
import fs2.Stream
import mtest.spark.*
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

@DoNotDiscover
class JacksonTest extends AnyFunSuite {

  def rooster(path: NJPath) =
    new RddAvroFileHoarder[IO, Rooster](RoosterData.rdd.repartition(3), Rooster.avroCodec.avroEncoder)
      .jackson(path)

  val hdp = sparkSession.hadoop[IO]

  def loadRooster(path: NJPath) = Stream
    .force(
      hdp
        .filesSortByName(path)
        .map(_.foldLeft(Stream.empty.covaryAll[IO, Rooster]) { case (ss, hip) =>
          ss ++ hdp.bytes
            .source(hip)
            .through(JacksonSerde.fromBytes[IO](Rooster.schema))
            .map(Rooster.avroCodec.fromRecord)
        }))
    .compile
    .toList
    .map(_.toSet)

  val root = NJPath("./data/test/spark/persist/jackson/")
  test("datetime read/write identity - uncompressed") {
    val path = root / "rooster" / "uncompressed"
    rooster(path).errorIfExists.ignoreIfExists.overwrite.uncompress.run.unsafeRunSync()
    val r = loaders.rdd.jackson[Rooster](path, sparkSession, Rooster.avroCodec.avroDecoder)
    assert(RoosterData.expected == r.collect().toSet)
    assert(RoosterData.expected == loadRooster(path).unsafeRunSync())
  }

  def bee(path: NJPath) =
    new RddAvroFileHoarder[IO, Bee](BeeData.rdd.repartition(3), Bee.avroCodec.avroEncoder).jackson(path)

  test("byte-array read/write identity - multi") {
    import cats.implicits.*
    val path = root / "bee" / "uncompressed"
    bee(path).uncompress.run.unsafeRunSync()
    val t = loaders.rdd.jackson[Bee](path, sparkSession, Bee.avroCodec.avroDecoder).collect().toList
    assert(BeeData.bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("byte-array read/write identity - multi.gzip") {
    import cats.implicits.*
    val path = root / "bee" / "gzip"
    bee(path).gzip.run.unsafeRunSync()
    val t = loaders.rdd.jackson[Bee](path, sparkSession, Bee.avroCodec.avroDecoder).collect().toList
    assert(BeeData.bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("byte-array read/write identity - multi.bzip2") {
    import cats.implicits.*
    val path = root / "bee" / "bzip2"
    bee(path).bzip2.run.unsafeRunSync()
    val t = loaders.rdd.jackson[Bee](path, sparkSession, Bee.avroCodec.avroDecoder).collect().toList
    assert(BeeData.bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("byte-array read/write identity - multi.deflate") {
    import cats.implicits.*
    val path = root / "bee" / "deflate"
    bee(path).deflate(9).run.unsafeRunSync()
    val t = loaders.rdd.jackson[Bee](path, sparkSession, Bee.avroCodec.avroDecoder).collect().toList
    assert(BeeData.bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("byte-array read/write identity - multi.lz4") {
    import cats.implicits.*
    val path = root / "bee" / "lz4"
    bee(path).lz4.run.unsafeRunSync()
    val t = loaders.rdd.jackson[Bee](path, sparkSession, Bee.avroCodec.avroDecoder).collect().toList
    assert(BeeData.bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("byte-array read/write identity - multi.snappy") {
    import cats.implicits.*
    val path = root / "bee" / "snappy"
    bee(path).snappy.run.unsafeRunSync()
    val t = loaders.rdd.jackson[Bee](path, sparkSession, Bee.avroCodec.avroDecoder).collect().toList
    assert(BeeData.bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("jackson jacket") {
    val path = NJPath("./data/test/spark/persist/jackson/jacket.json")
    val saver =
      new RddAvroFileHoarder[IO, Jacket](JacketData.rdd.repartition(3), Jacket.avroCodec.avroEncoder)
        .jackson(path)
    saver.run.unsafeRunSync()
    val t = loaders.rdd.jackson(path, sparkSession, Jacket.avroCodec.avroDecoder)
    assert(JacketData.expected.toSet == t.collect().toSet)
  }

}
