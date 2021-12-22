package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.datetime.crontabs
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.{MetricsReport, PassThrough, ServiceStopped}
import io.circe.Decoder
import io.circe.generic.auto.*
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final case class PassThroughObject(a: Int, b: String)

class PassThroughTest extends AnyFunSuite {
  val guard = TaskGuard[IO]("test").service("pass-throught")
  test("pass-through") {
    val PassThroughObject(a, b) :: rest = guard.eventStream { action =>
      List.range(0, 9).traverse(n => action.broker("pt").asError.passThrough(PassThroughObject(n, "a")))
    }.map {
      case PassThrough(_, _, _, _, v) => Decoder[PassThroughObject].decodeJson(v).toOption
      case _                          => None
    }.unNone.compile.toList.unsafeRunSync()
    assert(a == 0)
    assert(b == "a")
    assert(rest.last.a == 8)
    assert(rest.size == 8)
  }

  test("unsafe pass-through") {
    val List(PassThroughObject(a, b)) = guard.eventStream { action =>
      IO(1).map(_ => action.broker("pt").unsafePassThrough(PassThroughObject(1, "a")))
    }.debug()
      .map {
        case PassThrough(_, _, _, _, v) => Decoder[PassThroughObject].decodeJson(v).toOption
        case _                          => None
      }
      .unNone
      .compile
      .toList
      .unsafeRunSync()
    assert(a == 1)
    assert(b == "a")
  }

  test("counter") {
    val Some(last) = guard
      .updateConfig(_.withMetricSchedule(crontabs.bihourly))
      .eventStream(_.counter("counter").asError.increase(1).delayBy(1.second).replicateA(3))
      .debug()
      .compile
      .last
      .unsafeRunSync()
    assert(last.asInstanceOf[ServiceStopped].snapshot.counters("02.attention.counter.[counter/0135a608]") == 3)
  }

  test("counter - replace") {
    val Some(last) = guard
      .updateConfig(_.withMetricSchedule(crontabs.bihourly))
      .eventStream(_.counter("counter").replace(5).delayBy(1.second).replicateA(3))
      .compile
      .last
      .unsafeRunSync()
    assert(last.asInstanceOf[ServiceStopped].snapshot.counters("20.counter.[counter/0135a608]") == 5)
  }

  test("warn") {
    val Some(last) = guard
      .updateConfig(_.withMetricSchedule(crontabs.c997))
      .eventStream(_.alert("oops").error("message").delayBy(1.second))
      .interruptAfter(5.seconds)
      .compile
      .last
      .unsafeRunSync()
    assert(last.asInstanceOf[ServiceStopped].snapshot.counters("02.attention.alert.error.[oops/a32b945e]") == 1)
  }
}
