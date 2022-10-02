package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.codahale.metrics.MetricFilter
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.*
import com.github.chenharryhua.nanjin.guard.event.NJEvent.*
import com.github.chenharryhua.nanjin.guard.observers.{console, logging}
import eu.timepit.refined.auto.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalatest.funsuite.AnyFunSuite

import java.time.ZoneId
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class HealthCheckTest extends AnyFunSuite {
  val guard: TaskGuard[IO] = TaskGuard[IO]("health-check")
  test("1.should receive 3 MetricsReport event") {
    val s :: a :: b :: c :: _ = guard
      .updateConfig(_.withZoneId(ZoneId.of("Australia/Sydney")))
      .service("normal")
      .withJmxReporter(_.inDomain("abc"))
      .withMetricFilter(MetricFilter.startsWith("01"))
      .updateConfig(_.withMetricReport(2.seconds))
      .eventStream(gd => gd.action(_.notice).retry(IO.never[Int]).run("cron"))
      .map(e => decode[NJEvent](e.asJson.noSpaces).toOption)
      .unNone
      .interruptAfter(9.second)
      .evalTap(logging.simple[IO])
      .compile
      .toList
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[MetricReport])
    assert(c.isInstanceOf[MetricReport])
    assert(c.isInstanceOf[MetricReport])
  }

  test("2.success-test") {
    val s :: a :: b :: c :: d :: _ = guard
      .service("success-test")
      .updateConfig(_.withMetricReport(1.second))
      .eventStream(gd =>
        gd.action(_.notice).retry(IO(1)).run("a") >> gd.action(_.notice).retry(IO.never).run("b"))
      .evalTap(console.simple[IO])
      .map(e => decode[NJEvent](e.asJson.noSpaces).toOption)
      .unNone
      .evalTap(logging.simple[IO])
      .interruptAfter(5.second)
      .compile
      .toList
      .unsafeRunSync()
    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionSucc])
    assert(c.isInstanceOf[ActionStart])
    assert(d.isInstanceOf[MetricReport])
  }

  test("3.never success") {
    val s :: a :: b :: c :: _ = guard
      .service("metrics-report")
      .updateConfig(
        _.withMetricReport(1.second)
          .withConstantDelay(1.hour)
          .withMetricDurationTimeUnit(TimeUnit.MICROSECONDS)
          .withMetricRateTimeUnit(TimeUnit.MINUTES))
      .eventStream(gd =>
        gd.action(_.notice.withConstantDelay(300.second, 10).withCapDelay(2.seconds))
          .retry(IO.raiseError(new Exception))
          .run("not/fail/yet"))
      .interruptAfter(5.second)
      .evalTap(logging.simple[IO])
      .map(e => decode[NJEvent](e.asJson.noSpaces).toOption)
      .unNone
      .compile
      .toList
      .unsafeRunSync()
    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[MetricReport])
  }

  test("4.metrics reset") {
    val list = guard
      .service("metrics-reset-test")
      .updateConfig(_.withMetricReport(2.seconds).withMetricReset(trisecondly))
      .eventStream(_.action(_.silent).retry(IO.never).run("ok"))
      .map(e => decode[NJEvent](e.asJson.noSpaces).toOption)
      .unNone
      .evalTap(logging.simple[IO])
      .interruptAfter(7.second)
      .compile
      .toList
      .unsafeRunSync()
    assert(list.nonEmpty)
  }
}
