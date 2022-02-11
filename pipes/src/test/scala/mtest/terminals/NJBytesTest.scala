package mtest.terminals

import akka.stream.scaladsl.Source
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.pipes.serde.{BinaryAvroSerde, CirceSerde, TextSerde}
import com.github.chenharryhua.nanjin.terminals.{NJHadoop, NJPath}
import eu.timepit.refined.auto.*
import fs2.Stream
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.DeflateCodec
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random
object HadoopTestData {

  val pandaSchema: Schema = (new Schema.Parser).parse("""
                                                        |{
                                                        |  "type": "record",
                                                        |  "name": "Panda",
                                                        |  "namespace": "mtest.HadoopTestData",
                                                        |  "fields": [
                                                        |    {
                                                        |      "name": "name",
                                                        |      "type": "string"
                                                        |    },
                                                        |    {
                                                        |      "name": "age",
                                                        |      "type": "int"
                                                        |    },
                                                        |    {
                                                        |      "name": "id",
                                                        |      "type": "int"
                                                        |    }
                                                        |  ]
                                                        |}
                                                        |""".stripMargin)

  val youngPanda = new GenericData.Record(pandaSchema)
  youngPanda.put("name", "zhouzhou")
  youngPanda.put("age", 8)
  youngPanda.put("id", Random.nextInt())

  val prettyPanda = new GenericData.Record(pandaSchema)
  prettyPanda.put("name", "fanfan")
  prettyPanda.put("age", 8)
  prettyPanda.put("id", Random.nextInt())

  val pandas: List[GenericRecord] = List(youngPanda, prettyPanda)

  val cfg               = new Configuration()
  val hdp: NJHadoop[IO] = NJHadoop[IO](cfg)
}

class NJBytesTest extends AnyFunSuite {
  import HadoopTestData.*

  test("hadoop text write/read identity") {
    val pathStr = NJPath("./data/test/devices/") / "greeting.txt"
    hdp.delete(pathStr).unsafeRunSync()
    val testString           = s"hello hadoop ${Random.nextInt()}"
    val ts: Stream[IO, Byte] = Stream(testString).through(fs2.text.utf8.encode)
    val sink                 = hdp.bytes.sink(pathStr)
    val src                  = hdp.bytes.source(pathStr)
    ts.through(sink).compile.drain.unsafeRunSync()
    val action = src.through(fs2.text.utf8.decode).compile.toList
    assert(action.unsafeRunSync().mkString == testString)
  }

  test("deflate text write/read akka") {
    val pathStr = NJPath("./data/test/devices/akka.txt.deflate")
    hdp.delete(pathStr).unsafeRunSync()
    val ts   = Source(List("string1", "string2", "string3")).via(TextSerde.akka.toByteString)
    val src  = hdp.bytes.akka.source(pathStr)
    val sink = hdp.bytes.withCompressionCodec(new DeflateCodec()).akka.sink(pathStr)
    IO.fromFuture(IO(ts.runWith(sink))).unsafeRunSync()
    val action =
      IO.fromFuture(IO(src.via(TextSerde.akka.fromByteString).runFold(List.empty[String]) { case (ss, i) =>
        ss.appended(i)
      }))

    assert(action.unsafeRunSync() == List("string1", "string2", "string3"))
  }

  test("deflate binary avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.binary.avro.deflate")
    hdp.delete(pathStr).unsafeRunSync()
    val ts   = Stream.emits(pandas).covary[IO]
    val src  = hdp.bytes.source(pathStr)
    val sink = hdp.bytes.withCompressionCodec(new DeflateCodec()).sink(pathStr)
    ts.through(BinaryAvroSerde.toBytes(pandaSchema)).through(sink).compile.drain.unsafeRunSync()
    val action = src.through(BinaryAvroSerde.fromBytes(pandaSchema)).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("extension voilation") {
    val pathStr = NJPath("./data/test/devices/panda.binary.avro.deflate2")
    hdp.delete(pathStr).unsafeRunSync()
    val ts     = Stream.emits(pandas).covary[IO]
    val sink   = hdp.bytes.withCompressionCodec(new DeflateCodec()).sink(pathStr)
    val action = ts.through(BinaryAvroSerde.toBytes(pandaSchema)).through(sink).compile.drain
    assertThrows[Exception](action.unsafeRunSync())
  }

  test("uncompressed binary avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.uncompressed.binary.avro")
    hdp.delete(pathStr).unsafeRunSync()
    val ts   = Stream.emits(pandas).covary[IO]
    val sink = hdp.bytes.sink(pathStr)
    val src  = hdp.bytes.source(pathStr)
    ts.through(BinaryAvroSerde.toBytes(pandaSchema)).through(sink).compile.drain.unsafeRunSync()
    val action = src.through(BinaryAvroSerde.fromBytes(pandaSchema)).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("dataFolders") {
    val pathStr = NJPath("./data/test/devices")
    val folders = hdp.dataFolders(pathStr).unsafeRunSync()
    assert(folders.headOption.exists(_.pathStr.contains("devices")))
  }

  test("hadoop input files") {
    val path = NJPath("data/test/devices")
    hdp.filesByName(path).flatMap(_.traverse(x => IO.println(x.toString))).unsafeRunSync()
  }

  test("circe write/read") {
    val pathStr = NJPath("./data/test/devices/circe.json")
    hdp.delete(pathStr).unsafeRunSync()
    val data = Set(1, 2, 3)
    val ts   = Stream.emits(data.toList).covary[IO]
    val sink = hdp.bytes.sink(pathStr)
    val src  = hdp.bytes.source(pathStr)
    ts.through(CirceSerde.toBytes(true)).through(sink).compile.drain.unsafeRunSync()
    val action = src.through(CirceSerde.fromBytes[IO, Int]).compile.toList
    assert(action.unsafeRunSync().toSet == data)
  }

  test("circe write/read akka") {
    val pathStr = NJPath("./data/test/devices/akka.circe.json")
    val data    = Set(1, 2, 3)
    hdp.delete(pathStr).unsafeRunSync()
    val ts   = Source(data)
    val sink = hdp.bytes.akka.sink(pathStr)
    val src  = hdp.bytes.akka.source(pathStr)
    IO.fromFuture(IO(ts.via(CirceSerde.akka.toByteString(true)).runWith(sink))).unsafeRunSync()
    val action =
      IO.fromFuture(IO(src.via(CirceSerde.akka.fromByteString[Int]).runFold(Set.empty[Int]) { case (ss, i) => ss + i }))
    assert(action.unsafeRunSync() == data)
  }
}
