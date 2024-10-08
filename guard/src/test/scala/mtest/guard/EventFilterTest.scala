package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.common.chrono.zones.sydneyTime
import com.github.chenharryhua.nanjin.common.chrono.{tickStream, Policy}
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.MetricIndex.Periodic
import com.github.chenharryhua.nanjin.guard.event.NJEvent.{MetricReport, ServiceStart, ServiceStop}
import com.github.chenharryhua.nanjin.guard.event.eventFilters
import com.github.chenharryhua.nanjin.guard.service.{Agent, ServiceGuard}
import eu.timepit.refined.auto.*
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class EventFilterTest extends AnyFunSuite {
  private val service: ServiceGuard[IO] = TaskGuard[IO]("event.filters").service("filters")

  test("1.isPivotalEvent") {
    val List(a, b) = service.eventStream { agent =>
      agent.action("pivotal", _.bipartite).retry(IO(())).buildWith(identity).use(_.run(()))
    }.map(checkJson).filter(eventFilters.isPivotalEvent).compile.toList.unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ServiceStop])
  }

  test("2.isServiceEvent") {
    val List(a, b) = service.eventStream { agent =>
      agent.action("service", _.bipartite).retry(IO(())).buildWith(identity).use(_.run(()))
    }.map(checkJson).filter(eventFilters.isServiceEvent).compile.toList.unsafeRunSync()

    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ServiceStop])
  }

  test("3.nonSuppress") {
    val List(a, b) = service.eventStream { agent =>
      agent.action("nonSuppress", _.bipartite.suppressed).retry(IO(())).buildWith(identity).use(_.run(()))
    }.map(checkJson).filter(eventFilters.nonSuppress).compile.toList.unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ServiceStop])
  }

  private def sleepAction(agent: Agent[IO]): IO[Unit] =
    agent.action("sleep").retry(IO.sleep(7.seconds)).buildWith(identity).use(_.run(()))

  test("4.sampling - FiniteDuration") {
    val List(a, b, c, d) = service
      .updateConfig(_.withMetricReport(Policy.crontab(_.secondly)))
      .eventStream(sleepAction)
      .map(checkJson)
      .filter(eventFilters.sampling(3.seconds))
      .compile
      .toList
      .unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.asInstanceOf[MetricReport].index.asInstanceOf[Periodic].tick.index === 4)
    assert(c.asInstanceOf[MetricReport].index.asInstanceOf[Periodic].tick.index === 7)
    assert(d.isInstanceOf[ServiceStop])
  }

  test("5.sampling - divisor") {
    val List(a, b, c, d) = service
      .updateConfig(_.withMetricReport(Policy.crontab(_.secondly)))
      .eventStream(sleepAction)
      .map(checkJson)
      .filter(eventFilters.sampling(3))
      .compile
      .toList
      .unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.asInstanceOf[MetricReport].index.asInstanceOf[Periodic].tick.index === 3)
    assert(c.asInstanceOf[MetricReport].index.asInstanceOf[Periodic].tick.index === 6)
    assert(d.isInstanceOf[ServiceStop])
  }

  test("6.sampling - cron") {
    val policy = Policy.crontab(_.secondly)
    val align  = tickStream[IO](Policy.crontab(_.every3Seconds).limited(1), sydneyTime)
    val run = service
      .updateConfig(_.withMetricReport(policy))
      .eventStream(sleepAction)
      .map(checkJson)
      .filter(eventFilters.sampling(_.every3Seconds))

    val List(a, b, c, d) = align.flatMap(_ => run).debug().compile.toList.unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.asInstanceOf[MetricReport].index.asInstanceOf[Periodic].tick.index === 3)
    assert(c.asInstanceOf[MetricReport].index.asInstanceOf[Periodic].tick.index === 6)
    assert(d.isInstanceOf[ServiceStop])
  }
}
