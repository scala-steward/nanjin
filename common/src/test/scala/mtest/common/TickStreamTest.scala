package mtest.common

import cats.effect.IO
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.common.chrono.{policies, tickStream, Policy}
import cron4s.Cron
import org.scalatest.funsuite.AnyFunSuite

import java.time.temporal.ChronoUnit
import java.time.{Duration as JavaDuration, ZoneId}
import scala.concurrent.duration.DurationDouble
import scala.jdk.DurationConverters.{JavaDurationOps, ScalaDurationOps}

class TickStreamTest extends AnyFunSuite {
  val commonality: Policy =
    policies.crontab(Cron.unsafeParse("0-59 * * ? * *"))
  val zoneId: ZoneId = ZoneId.of("Australia/Sydney")
  test("1.tick") {
    val policy = commonality.limited(5)
    val ticks  = tickStream[IO](policy, zoneId)

    val res = ticks.map(_.interval.toScala).compile.toList.unsafeRunSync()
    assert(res.tail.forall(d => d === 1.seconds), res)
  }

  test("2.process longer than 1 second") {
    val policy = commonality
    val ticks  = tickStream[IO](policy, zoneId)

    val fds =
      ticks.evalTap(_ => IO.sleep(1.5.seconds)).take(5).compile.toList.unsafeRunSync()
    fds.tail.foreach { t =>
      val interval = t.interval.toScala
      assert(interval === 2.seconds)
    }
  }

  test("3.process less than 1 second") {
    val policy = commonality
    val ticks  = tickStream[IO](policy, zoneId)

    val fds =
      ticks.evalTap(_ => IO.sleep(0.5.seconds)).take(5).compile.toList.unsafeRunSync()
    fds.tail.foreach { t =>
      val interval = t.interval.toScala
      assert(interval === 1.seconds)
    }
  }

  test("4.ticks - session id should not be same") {
    val policy = commonality.limited(5)
    val ticks  = tickStream[IO](policy, zoneId).compile.toList.unsafeRunSync()
    assert(ticks.size === 5, ticks)
    val spend = ticks(4).wakeup.toEpochMilli / 1000 - ticks.head.wakeup.toEpochMilli / 1000
    assert(spend === 4, ticks)
    assert(ticks.map(_.sequenceId).distinct.size == 1)
  }

  test("6.cron") {
    val policy = commonality
    val ticks  = tickStream[IO](policy, zoneId)
    val sleep: IO[JavaDuration] =
      Random
        .scalaUtilRandom[IO]
        .flatMap(_.betweenLong(0, 500))
        .flatMap(d => IO.sleep(d.toDouble.millisecond).as(JavaDuration.ofMillis(d)))

    ticks
      .evalMap(t => sleep.map((t, _)))
      .sliding(2)
      .map { ck =>
        val (t1, l1) = ck(0)
        val (t2, _)  = ck(1)
        assert(t2.interval === JavaDuration.between(t1.wakeup, t2.wakeup))
        assert(t2.snooze.toScala < t2.interval.toScala)
        val dur = JavaDuration
          .between(t2.previous.truncatedTo(ChronoUnit.SECONDS), t2.wakeup.truncatedTo(ChronoUnit.SECONDS))
          .toScala
        assert(dur === 1.second)
        assert(t1.sequenceId === t2.sequenceId)
        assert(t2.previous === t1.wakeup)
        val j = JavaDuration.between(t2.previous.plus(l1).plus(t2.snooze), t2.wakeup).toNanos
        assert(j > 0)
        t1
      }
      .take(10)
      .compile
      .toList
      .unsafeRunSync()
  }

  test("7.constant") {
    val policy = policies.constant(1.second.toJava)
    val ticks  = tickStream[IO](policy, zoneId)
    val sleep: IO[JavaDuration] =
      Random
        .scalaUtilRandom[IO]
        .flatMap(_.betweenLong(0, 500))
        .flatMap(d => IO.sleep(d.toDouble.millisecond).as(JavaDuration.ofMillis(d)))

    ticks.evalTap(_ => sleep).take(10).debug().compile.toList.unsafeRunSync()
  }
  test("8.fixed pace") {
    val policy = policies.fixedPace(2.second.toJava)
    val ticks  = tickStream[IO](policy, zoneId)
    val sleep: IO[JavaDuration] =
      Random
        .scalaUtilRandom[IO]
        .flatMap(_.betweenLong(0, 2500))
        .flatMap(d => IO.sleep(d.toDouble.millisecond).as(JavaDuration.ofMillis(d)))

    ticks.evalTap(_ => sleep).take(10).map(_.wakeup).debug().compile.toList.unsafeRunSync()
  }

}
