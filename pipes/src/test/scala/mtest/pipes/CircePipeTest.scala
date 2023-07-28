package mtest.pipes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.pipes.CirceSerde
import com.github.chenharryhua.nanjin.terminals.{NJHadoop, NJPath}
import eu.timepit.refined.auto.*
import fs2.Stream
import io.circe.generic.auto.*
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite

class CircePipeTest extends AnyFunSuite {
  import TestData.*
  val data: Stream[IO, Tiger] = Stream.emits(tigers)
  val hd: NJHadoop[IO]        = NJHadoop[IO](new Configuration)
  val root                    = NJPath("./data/test/pipes/circe/")

  test("circe identity - remove null") {
    assert(
      data
        .through(CirceSerde.toBytes[IO, Tiger](isKeepNull = false))
        .through(CirceSerde.fromBytes[IO, Tiger])
        .compile
        .toList
        .unsafeRunSync() === tigers)
  }

  test("circe identity - keep null") {
    assert(
      data
        .through(CirceSerde.toBytes[IO, Tiger](isKeepNull = true))
        .through(CirceSerde.fromBytes[IO, Tiger])
        .compile
        .toList
        .unsafeRunSync() === tigers)
  }

  test("read/write test uncompressed") {
    val path = root / "circe.json"
    hd.delete(path).unsafeRunSync()
    val rst = hd.bytes.source(path).through(CirceSerde.fromBytes[IO, Tiger]).compile.toList
    data.through(CirceSerde.toBytes(true)).through(hd.bytes.sink(path)).compile.drain.unsafeRunSync()
    assert(rst.unsafeRunSync() == tigers)
  }

  test("read/write test gzip") {
    val path = root / "circe.json.gz"
    hd.delete(path).unsafeRunSync()
    val rst = hd.bytes.source(path).through(CirceSerde.fromBytes[IO, Tiger]).compile.toList
    data.through(CirceSerde.toBytes(true)).through(hd.bytes.sink(path)).compile.drain.unsafeRunSync()
    assert(rst.unsafeRunSync() == tigers)
  }

  test("read/write test snappy") {
    val path = root / "circe.json.snappy"
    hd.delete(path).unsafeRunSync()
    val rst = hd.bytes.source(path).through(CirceSerde.fromBytes[IO, Tiger]).compile.toList
    data.through(CirceSerde.toBytes(true)).through(hd.bytes.sink(path)).compile.drain.unsafeRunSync()
    assert(rst.unsafeRunSync() == tigers)
  }

  test("read/write bzip2") {
    val path = root / "circe.json.bz2"
    hd.delete(path).unsafeRunSync()
    val rst = hd.bytes.source(path).through(CirceSerde.fromBytes[IO, Tiger]).compile.toList
    data.through(CirceSerde.toBytes(true)).through(hd.bytes.sink(path)).compile.drain.unsafeRunSync()
    assert(rst.unsafeRunSync() == tigers)
  }

  test("read/write lz4") {
    val path = root / "circe.json.lz4"
    hd.delete(path).unsafeRunSync()
    val rst = hd.bytes.source(path).through(CirceSerde.fromBytes[IO, Tiger]).compile.toList
    data.through(CirceSerde.toBytes(true)).through(hd.bytes.sink(path)).compile.drain.unsafeRunSync()
    assert(rst.unsafeRunSync() == tigers)
  }

  test("read/write deflate") {
    val path = root / "circe.json.deflate"
    hd.delete(path).unsafeRunSync()
    val rst = hd.bytes.source(path).through(CirceSerde.fromBytes[IO, Tiger]).compile.toList
    data.through(CirceSerde.toBytes(true)).through(hd.bytes.sink(path)).compile.drain.unsafeRunSync()
    assert(rst.unsafeRunSync() == tigers)
  }
}
