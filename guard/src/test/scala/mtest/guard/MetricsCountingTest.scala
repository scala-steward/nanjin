package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.codahale.metrics.SlidingWindowReservoir
import com.github.chenharryhua.nanjin.common.chrono.Policy
import com.github.chenharryhua.nanjin.common.chrono.zones.sydneyTime
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.NJEvent.MetricReport
import com.github.chenharryhua.nanjin.guard.event.{
  retrieveAction,
  retrieveAlert,
  retrieveHealthChecks,
  retrieveHistogram,
  retrieveMeter,
  retrieveTimer
}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class MetricsCountingTest extends AnyFunSuite {
  private val task: TaskGuard[IO] =
    TaskGuard[IO]("metrics.counting").updateConfig(_.withZoneId(sydneyTime))
  private val policy = Policy.fixedDelay(1.seconds).limited(1)

  def counterSucc(mr: MetricReport): Long =
    retrieveAction.doneCount(mr.snapshot.counters).values.sum
  def counterFail(mr: MetricReport): Long =
    retrieveAction.failCount(mr.snapshot.counters).values.sum
  def counterRetry(mr: MetricReport): Long =
    retrieveAction.retryCount(mr.snapshot.counters).values.sum
  def counterAlertInfo(mr: MetricReport): Long =
    retrieveAlert.infoCount(mr.snapshot.counters).values.sum
  def counterAlertWarn(mr: MetricReport): Long =
    retrieveAlert.warnCount(mr.snapshot.counters).values.sum
  def counterAlertError(mr: MetricReport): Long =
    retrieveAlert.errorCount(mr.snapshot.counters).values.sum

  def timerSucc(mr: MetricReport): Long =
    retrieveAction.timer(mr.snapshot.timers).values.map(_.calls).sum

  def meter(mr: MetricReport): Long =
    retrieveMeter(mr.snapshot.meters).values.map(_.sum).sum

  def histogram(mr: MetricReport): Long =
    retrieveHistogram(mr.snapshot.histograms).values.map(_.updates).sum

  test("1.silent") {
    val mr = task
      .service("silent")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.silent.policy(policy))
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success").retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(mr.snapshot.timers.isEmpty)
    assert(mr.snapshot.counters.isEmpty)
  }

  test("2.silent.count") {
    val mr = task
      .service("silent.count")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.silent.policy(policy).counted)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.silent.counted).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(counterSucc(mr) == 1)
    assert(counterFail(mr) == 1)
    assert(counterRetry(mr) == 1)
  }

  test("3.silent.time") {
    val mr = task
      .service("silent.time")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.silent.policy(policy).timed)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.silent.timed).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(timerSucc(mr) == 1)
    assert(mr.snapshot.counters.isEmpty)

  }

  test("4.silent.time.count") {
    val mr = task
      .service("silent.time.count")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.silent.policy(policy).timed.counted)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.silent.timed.counted).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(timerSucc(mr) == 1)
    assert(counterSucc(mr) == 1)
    assert(counterFail(mr) == 1)
    assert(counterRetry(mr) == 1)
  }

  test("5.unipartite") {
    val mr = task
      .service("unipartite")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.unipartite.policy(policy))
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.unipartite).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(mr.snapshot.timers.isEmpty)
    assert(mr.snapshot.counters.isEmpty)
  }

  test("6.unipartite.count") {
    val mr = task
      .service("unipartite.count")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.unipartite.policy(policy).counted)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.unipartite.counted).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(counterSucc(mr) == 1)
    assert(counterFail(mr) == 1)
    assert(counterRetry(mr) == 1)
  }

  test("7.unipartite.time") {
    val mr = task
      .service("unipartite.time")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.unipartite.policy(policy).timed)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.unipartite.timed).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(timerSucc(mr) == 1)
    assert(mr.snapshot.counters.isEmpty)

  }

  test("8.unipartite.time.count") {
    val mr = task
      .service("unipartite.time.count")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.unipartite.policy(policy).timed.counted)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.unipartite.timed.counted).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(timerSucc(mr) == 1)
    assert(counterSucc(mr) == 1)
    assert(counterFail(mr) == 1)
    assert(counterRetry(mr) == 1)
  }

  test("9.bipartite") {
    val mr = task
      .service("bipartite")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.bipartite.policy(policy))
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.bipartite).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(mr.snapshot.timers.isEmpty)
    assert(mr.snapshot.counters.isEmpty)
  }

  test("10.bipartite.count") {
    val mr = task
      .service("bipartite.count")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.bipartite.policy(policy).counted)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.bipartite.counted).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get

    assert(counterSucc(mr) == 1)
    assert(counterFail(mr) == 1)
    assert(counterRetry(mr) == 1)
  }

  test("11.bipartite.time") {
    val mr = task
      .service("bipartite.time")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.bipartite.policy(policy).timed)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.bipartite.timed).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(timerSucc(mr) == 1)
    assert(mr.snapshot.counters.isEmpty)
  }

  test("12.bipartite.time.count") {
    val mr = task
      .service("bipartite.time.count")
      .eventStream { ga =>
        val run = for {
          a1 <- ga
            .action("fail", _.bipartite.policy(policy).timed.counted)
            .retry(IO.raiseError[Int](new Exception))
            .buildWith(identity)
          a2 <- ga.action("success", _.bipartite.timed.counted).retry(IO(0)).buildWith(identity)
        } yield a1.run(()).attempt >> a2.run(())
        run.use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(timerSucc(mr) == 1)
    assert(counterSucc(mr) == 1)
    assert(counterFail(mr) == 1)
    assert(counterRetry(mr) == 1)
  }

  test("13.alert") {
    val mr = task
      .service("alert")
      .eventStream { ag =>
        ag.alert("oops", _.counted).use { alert =>
          alert.warn(Some("m1")) >>
            alert.info(Some("m2")) >>
            alert.error(Some("m3")) >>
            ag.metrics.report
        }
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(counterAlertInfo(mr) == 1)
    assert(counterAlertWarn(mr) == 1)
    assert(counterAlertError(mr) == 1)
  }

  test("14.counter") {
    task
      .service("counter")
      .eventStream { ga =>
        ga.counter("counter").use { c =>
          c.unsafeInc(2)
          c.unsafeDec(1)
          c.inc(2) >> ga.metrics.report
        }
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
  }

  test("15.meter") {
    val mr = task
      .service("meter")
      .eventStream { ga =>
        ga.meter("counter").use { meter =>
          meter.unsafeUpdate(1)
          meter.update(2) >> ga.metrics.report
        }
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get
    assert(meter(mr) == 3)
  }

  test("16.histogram") {
    val mr = task
      .service("histogram")
      .eventStream { ga =>
        ga.histogram("histogram", _.withReservoir(new SlidingWindowReservoir(5))).use { histo =>
          histo.unsafeUpdate(100)
          histo.update(200) >> ga.metrics.report
        }
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get

    assert(histogram(mr) == 2)
  }

  test("17.gauge") {
    task
      .service("gauge")
      .eventStream { agent =>
        val g2 = agent.gauge("exception").register(IO.raiseError[Int](new Exception))
        val g3 = agent.gauge("good").register(IO(10))
        g2.both(g3).surround(IO.sleep(3.seconds) >> agent.metrics.report)
      }
      .map(checkJson)
      .map {
        case event: MetricReport => assert(event.snapshot.gauges.size == 2)
        case _                   => ()
      }
      .compile
      .drain
      .unsafeRunSync()
  }

  test("18.health-check") {
    val res = task
      .service("heal-check")
      .eventStream { ga =>
        (ga.healthCheck("c1").register(IO(true)) >> ga.healthCheck("c2").register(IO(true))).use(_ =>
          ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .lastOrError
      .unsafeRunSync()

    val hc = retrieveHealthChecks(res.snapshot.gauges)
    assert(hc.size == 2)
    assert(hc.values.forall(identity))
  }

  test("19.timer") {
    val mr = task
      .service("timer")
      .eventStream { ga =>
        ga.timer("timer", _.withReservoir(new SlidingWindowReservoir(10))).use { timer =>
          timer.unsafeUpdate(2.seconds)
          timer.update(2.minutes) >>
            ga.metrics.report
        }
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .last
      .unsafeRunSync()
      .get

    assert(retrieveTimer(mr.snapshot.timers).values.map(_.calls).sum == 2)
  }

  test("20.dup action") {
    val mr = task
      .service("dup")
      .eventStream { ga =>
        val act1 = ga.action("job", _.timed).retry(IO(0)).buildWith(identity)
        (act1, act1).mapN((a1, a2) => a1.run(()) >> a2.run(())).use(_ >> ga.metrics.report)
      }
      .map(checkJson)
      .mapFilter(metricReport)
      .compile
      .lastOrError
      .unsafeRunSync()
    assert(timerSucc(mr) == 2)
  }
}
