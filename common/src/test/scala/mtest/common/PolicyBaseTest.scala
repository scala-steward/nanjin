package mtest.common

import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.chrono.*
import com.github.chenharryhua.nanjin.common.sequence.*
import io.circe.jawn.decode
import io.circe.syntax.EncoderOps
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps
class PolicyBaseTest extends AnyFunSuite {

  test("fibonacci") {
    assert(fibonacci.take(10).toList == List(1, 1, 2, 3, 5, 8, 13, 21, 34, 55))
    assert(exponential.take(10).toList == List(1, 2, 4, 8, 16, 32, 64, 128, 256, 512))
    assert(primes.take(10).toList == List(2, 3, 5, 7, 11, 13, 17, 19, 23, 29))
  }

  test("fixed delay") {
    val policy = Policy.fixedDelay(1.second, 0.second)
    println(policy.show)
    assert(decode[Policy](policy.asJson.noSpaces).toOption.get == policy)

    val ts: TickStatus = zeroTickStatus.renewPolicy(policy)

    val List(a1, a2, a3, a4, a5) = tickLazyList(ts).take(5).toList

    assert(a1.sequenceId == ts.tick.sequenceId)
    assert(a1.launchTime == ts.tick.launchTime)
    assert(a1.index == 1)
    assert(a1.previous === ts.tick.wakeup)
    assert(a1.snooze == 1.second.toJava)

    assert(a2.sequenceId == ts.tick.sequenceId)
    assert(a2.launchTime == ts.tick.launchTime)
    assert(a2.index == 2)
    assert(a2.previous === a1.wakeup)
    assert(a2.snooze == 0.second.toJava)

    assert(a3.sequenceId == ts.tick.sequenceId)
    assert(a3.launchTime == ts.tick.launchTime)
    assert(a3.index == 3)
    assert(a3.previous === a2.wakeup)
    assert(a3.snooze == 1.second.toJava)

    assert(a4.sequenceId == ts.tick.sequenceId)
    assert(a4.launchTime == ts.tick.launchTime)
    assert(a4.index == 4)
    assert(a4.previous === a3.wakeup)
    assert(a4.snooze == 0.second.toJava)

    assert(a5.sequenceId == ts.tick.sequenceId)
    assert(a5.launchTime == ts.tick.launchTime)
    assert(a5.index == 5)
    assert(a5.previous === a4.wakeup)
    assert(a5.snooze == 1.second.toJava)
  }

  test("fixed rate") {
    val policy = Policy.fixedRate(1.second)
    println(policy.show)
    assert(decode[Policy](policy.asJson.noSpaces).toOption.get == policy)

    val ts: TickStatus = zeroTickStatus.renewPolicy(policy)

    val List(a1, a2, a3, a4, a5) = tickLazyList(ts).take(5).toList

    assert(a1.sequenceId == ts.tick.sequenceId)
    assert(a1.launchTime == ts.tick.launchTime)
    assert(a1.index == 1)
    assert(a1.previous === ts.tick.wakeup)
    assert(a1.wakeup == a1.previous.plus(1.seconds.toJava))

    assert(a2.sequenceId == ts.tick.sequenceId)
    assert(a2.launchTime == ts.tick.launchTime)
    assert(a2.index == 2)
    assert(a2.previous === a1.wakeup)
    assert(a2.wakeup == a2.previous.plus(1.seconds.toJava))

    assert(a3.sequenceId == ts.tick.sequenceId)
    assert(a3.launchTime == ts.tick.launchTime)
    assert(a3.index == 3)
    assert(a3.previous === a2.wakeup)
    assert(a3.wakeup == a3.previous.plus(1.seconds.toJava))

    assert(a4.sequenceId == ts.tick.sequenceId)
    assert(a4.launchTime == ts.tick.launchTime)
    assert(a4.index == 4)
    assert(a4.previous === a3.wakeup)
    assert(a4.wakeup == a4.previous.plus(1.seconds.toJava))

    assert(a5.sequenceId == ts.tick.sequenceId)
    assert(a5.launchTime == ts.tick.launchTime)
    assert(a5.index == 5)
    assert(a5.previous === a4.wakeup)
    assert(a5.wakeup == a5.previous.plus(1.seconds.toJava))
  }

  test("fixed delays") {
    val policy = Policy.fixedDelay(1.second, 2.seconds, 3.seconds)
    println(policy.show)
    assert(decode[Policy](policy.asJson.noSpaces).toOption.get == policy)

    val ts: TickStatus = zeroTickStatus.renewPolicy(policy)

    val List(a1, a2, a3, a4, a5, a6, a7) = tickLazyList(ts).take(7).toList

    assert(a1.index == 1)
    assert(a2.index == 2)
    assert(a3.index == 3)
    assert(a4.index == 4)
    assert(a5.index == 5)
    assert(a6.index == 6)
    assert(a7.index == 7)

    assert(a1.snooze == 1.second.toJava)
    assert(a2.snooze == 2.second.toJava)
    assert(a3.snooze == 3.second.toJava)
    assert(a4.snooze == 1.second.toJava)
    assert(a5.snooze == 2.second.toJava)
    assert(a6.snooze == 3.second.toJava)
    assert(a7.snooze == 1.second.toJava)
  }

  test("cron") {
    val policy = Policy.crontab(_.hourly)
    println(policy.show)
    println(policy.asJson)
    assert(decode[Policy](policy.asJson.noSpaces).toOption.get == policy)

    val ts = zeroTickStatus.renewPolicy(policy)

    val a1 = ts.next(ts.tick.launchTime.plus(1.hour.toJava)).get
    val a2 = a1.next(a1.tick.wakeup.plus(30.minutes.toJava)).get
    val a3 = a2.next(a2.tick.wakeup.plus(45.minutes.toJava)).get
    val a4 = a3.next(a2.tick.wakeup.plus(60.minutes.toJava)).get

    assert(a1.tick.index == 1)
    assert(a2.tick.index == 2)
    assert(a3.tick.index == 3)
    assert(a4.tick.index == 4)

    assert(a2.tick.snooze == 30.minutes.toJava)
    assert(a3.tick.snooze == 15.minutes.toJava)
    assert(a4.tick.snooze == 1.hour.toJava)
  }

  test("giveUp") {
    val policy = Policy.giveUp
    println(policy.show)
    assert(decode[Policy](policy.asJson.noSpaces).toOption.get == policy)

    val ts = zeroTickStatus.renewPolicy(policy)
    assert(ts.next(Instant.now).isEmpty)
  }
}
