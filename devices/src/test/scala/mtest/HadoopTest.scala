package mtest

import cats.effect.IO
import cats.implicits._
import com.github.chenharryhua.nanjin.devices.NJHadoop
import fs2.Stream
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite

class HadoopTest extends AnyFunSuite {
  val c          = new Configuration()
  val h          = new NJHadoop[IO](c, blocker)
  val pathStr    = "./data/hadoop/test.txt"
  val testString = "save string to hadoop"

  val ts: Stream[IO, Byte] =
    Stream(testString).through(fs2.text.utf8Encode)

  test("write/read identity") {
    val action = h.delete(pathStr) >>
      ts.through(h.hadoopSink(pathStr)).compile.drain >>
      h.hadoopSource(pathStr).through(fs2.text.utf8Decode).compile.toList
    assert(action.unsafeRunSync.head == testString)
  }
}
