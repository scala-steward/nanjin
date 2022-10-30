package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.NJEvent.*
import com.github.chenharryhua.nanjin.guard.service.ServiceGuard
import eu.timepit.refined.auto.*
import org.scalatest.funsuite.AnyFunSuite

import java.time.{ZoneId, ZonedDateTime}

class ConfigTest extends AnyFunSuite {
  val service: ServiceGuard[IO] =
    TaskGuard[IO]("config").service("config").updateConfig(_.withMetricDailyReset.withMetricReport(hourly))

  test("1.counting") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.notice.withCounting).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync().get.asInstanceOf[ActionStart]
    assert(as.actionInfo.actionParams.isCounting)
  }
  test("2.without counting") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.notice.withoutCounting).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync().get.asInstanceOf[ActionStart]
    assert(!as.actionInfo.actionParams.isCounting)
  }

  test("3.timing") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.notice.withTiming).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync().get.asInstanceOf[ActionStart]
    assert(as.actionInfo.actionParams.isTiming)
  }

  test("4.without timing") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.notice.withoutTiming).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync().get.asInstanceOf[ActionStart]
    assert(!as.actionInfo.actionParams.isTiming)
  }

  test("5.notice") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.notice).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync().get.asInstanceOf[ActionStart]
    assert(as.actionInfo.actionParams.isNotice)
  }

  test("6.critical") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.critical).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync().get.asInstanceOf[ActionStart]
    assert(as.actionInfo.actionParams.isCritical)
  }
  test("7.trivial") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.trivial).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync()
    assert(as.isEmpty)
  }

  test("8.silent") {
    val as = service.eventStream { agent =>
      agent.action("cfg", _.silent).retry(IO(1)).run
    }.filter(_.isInstanceOf[ActionStart]).compile.last.unsafeRunSync()
    assert(as.isEmpty)
  }

  test("9.report") {
    val as = service
      .updateConfig(_.withoutMetricReport)
      .eventStream { agent =>
        agent.action("cfg", _.silent).retry(IO(1)).run
      }
      .filter(_.isInstanceOf[ServiceStart])
      .compile
      .last
      .unsafeRunSync()
    assert(as.get.serviceParams.metricParams.reportSchedule.isEmpty)
  }

  test("10.reset") {
    val as = service
      .updateConfig(_.withoutMetricReset)
      .eventStream { agent =>
        agent.action("cfg", _.silent).retry(IO(1)).run
      }
      .filter(_.isInstanceOf[ServiceStart])
      .compile
      .last
      .unsafeRunSync()
    assert(as.get.serviceParams.metricParams.resetSchedule.isEmpty)
  }

  test("11.MonthlyReset - 00:00:01 of 1st day of the month") {
    val zoneId = ZoneId.of("Australia/Sydney")
    TaskGuard[IO]("monthly")
      .service("reset")
      .updateConfig(_.withMetricMonthlyReset)
      .eventStream { ag =>
        val now      = ZonedDateTime.of(2022, 10, 26, 0, 0, 0, 0, zoneId)
        val ns       = ag.serviceParams.metricParams.nextReset(now).get
        val expected = ZonedDateTime.of(2022, 11, 1, 0, 0, 1, 0, zoneId)
        assert(ns === expected)
        IO(())
      }
      .compile
      .drain
      .unsafeRunSync()
  }

  test("12.WeeklyReset - 00:00:01 on Monday") {
    val zoneId = ZoneId.of("Australia/Sydney")
    TaskGuard[IO]("weekly")
      .updateConfig(_.withZoneId(zoneId))
      .service("reset")
      .updateConfig(_.withMetricWeeklyReset)
      .eventStream { ag =>
        val now      = ZonedDateTime.of(2022, 10, 26, 0, 0, 0, 0, zoneId)
        val ns       = ag.serviceParams.metricParams.nextReset(now).get
        val expected = ZonedDateTime.of(2022, 10, 31, 0, 0, 1, 0, zoneId)
        assert(ns === expected)
        IO(())
      }
      .compile
      .drain
      .unsafeRunSync()
  }
}
