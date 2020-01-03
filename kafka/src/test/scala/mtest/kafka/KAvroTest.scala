package mtest.kafka

import org.scalatest.funsuite.AnyFunSuite
import cats.Show
import cats.derived.auto.show._
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka._
import com.github.chenharryhua.nanjin.kafka.codec.iso._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.effect.IO

sealed trait Color2
final case class Red(str: String, i: Int) extends Color2
final case class Green(str: String) extends Color2
final case class Blue(str: String) extends Color2
final case class Cloth(color: Color2, name: String, size: Int)

class KAvroTest extends AnyFunSuite {

  val topic = ctx.topic[Int, Cloth]("cloth")
  val p = topic.producer[IO]
  val c = topic.consumer[IO]
  test("should support coproduct") {
    val b = Cloth(Blue("b"), "blue-cloth", 1)
    val r = Cloth(Red("r", 1), "red-cloth", 2)
    val g = Cloth(Green("g"), "green-cloth", 3)
    val run =
      p.send(1, r) >>
        p.send(2, g) >>
        p.send(3, b) >>
        c.retrieveLastRecords.map(m => topic.decoder(m.head).decode)
    assert(run.unsafeRunSync().value() === b)
  }
}
