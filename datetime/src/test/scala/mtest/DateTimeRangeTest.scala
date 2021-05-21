package mtest

import cats.Eq
import cats.kernel.laws.discipline.PartialOrderTests
import cats.laws.discipline.AlternativeTests
import cats.syntax.all._
import com.fortysevendeg.scalacheck.datetime.jdk8.ArbitraryJdk8._
import com.github.chenharryhua.nanjin.datetime._
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.Configuration
import org.typelevel.discipline.scalatest.FunSuiteDiscipline

import java.sql.Timestamp
import java.time._
import scala.concurrent.duration._
import scala.util.Random

class DateTimeRangeTest extends AnyFunSuite with FunSuiteDiscipline with Configuration {

  implicit val arbiNJDateTimeRange: Arbitrary[NJDateTimeRange] =
    Arbitrary(for {
      date <- genZonedDateTimeWithZone(None)
      inc <- Gen.choose[Long](1, 50 * 365 * 24 * 3600) // 50 years
      d = date.toLocalDateTime
    } yield NJDateTimeRange(darwinTime).withStartTime(d).withEndTime(d.plusSeconds(inc)))

  implicit val cogen: Cogen[NJDateTimeRange] =
    Cogen(m => m.startTimestamp.map(_.milliseconds).getOrElse(0))

  implicit val arbParser    = Arbitrary(Gen.const(DateTimeParser.instantParser))
  implicit val cogenInstant = Cogen((i: Instant) => i.getEpochSecond)

  implicit val eqInstant = new Eq[DateTimeParser[Instant]] {
    //TODO: how to compare two parsers?
    override def eqv(x: DateTimeParser[Instant], y: DateTimeParser[Instant]): Boolean = true
  }

  implicit val eqInstant3 = new Eq[DateTimeParser[(Instant, Instant, Instant)]] {

    override def eqv(
      x: DateTimeParser[(Instant, Instant, Instant)],
      y: DateTimeParser[(Instant, Instant, Instant)]): Boolean = true
  }

  implicit val arbFunction = Arbitrary(
    Gen
      .function1[Instant, Instant](genZonedDateTime.map(_.toInstant))
      .map(f => DateTimeParser.alternativeDateTimeParser.pure(f)))

  checkAll("NJDateTimeRange-UpperBounded", PartialOrderTests[NJDateTimeRange].partialOrder)
  checkAll("NJDateTimeRange-PartialOrder", PartialOrderTests[NJDateTimeRange].partialOrder)
  checkAll("NJTimestamp", AlternativeTests[DateTimeParser].alternative[Instant, Instant, Instant])

  test("order of applying time data does not matter") {
    val zoneId    = ZoneId.of("Asia/Chongqing")
    val startTime = LocalDateTime.of(2012, 10, 26, 18, 0, 0)
    val endTime   = LocalDateTime.of(2012, 10, 26, 23, 0, 0)

    val param = NJDateTimeRange(sydneyTime)

    val a = param.withEndTime(endTime).withZoneId(zoneId).withStartTime(startTime)
    val b = param.withStartTime(startTime).withZoneId(zoneId).withEndTime(endTime)
    val c = param.withZoneId(zoneId).withStartTime(startTime).withEndTime(endTime)
    val d = param.withEndTime(endTime).withStartTime(startTime.atZone(zoneId)).withZoneId(zoneId)
    val e = param.withEndTime("2012-10-26T23:00:00").withStartTime("2012-10-26T18:00:00").withZoneId(zoneId)

    assert(a.eqv(b))
    assert(a.eqv(c))
    assert(a.eqv(d))
    assert(a.eqv(e))
    assert(a.zonedStartTime.get.eqv(startTime.atZone(zoneId)))
    assert(a.zonedEndTime.get.eqv(endTime.atZone(zoneId)))
  }
  test("days should return list of date from start(inclusive) to end(exclusive)") {
    val d1 = LocalDate.of(2012, 10, 26)
    val d2 = LocalDate.of(2012, 10, 27)
    val d3 = LocalDate.of(2012, 10, 28)

    val dtr = NJDateTimeRange(beijingTime).withStartTime(d1).withEndTime("2012-10-28")

    assert(dtr.days.eqv(List(d1, d2)))

    assert(dtr.withOneDay(d3).days.eqv(List(d3)))
  }

  test("infinite range should return empty list") {
    assert(NJDateTimeRange(cairoTime).days.isEmpty)
  }

  test("days of same day should return empty list") {
    val d3  = LocalDate.of(2012, 10, 28)
    val dt4 = LocalDateTime.of(d3, LocalTime.of(10, 1, 1))
    val dt5 = LocalDateTime.of(d3, LocalTime.of(10, 1, 2))

    val sameDay = NJDateTimeRange(newyorkTime).withStartTime(dt4).withEndTime(dt5)
    assert(sameDay.days.isEmpty)
  }

  test("days") {
    val dr =
      NJDateTimeRange(sydneyTime).withStartTime("2020-12-20T23:00:00+11:00").withEndTime("2020-12-29T01:00:00+11:00")

    assert((dr.endTimestamp, dr.startTimestamp).mapN { (e, s) =>
      assert(e.timeUnit == s.timeUnit)
      assert(e.sqlTimestamp.compareTo(s.sqlTimestamp) > 0)
      assert(e.javaLong.compareTo(s.javaLong) > 0)
      println(e.`Year=yyyy/Month=mm/Day=dd`(sydneyTime))
      println(s.`yyyy-mm-dd`(sydneyTime))
      e - s
    }.get.toDays == 8)
    println(dr.toString)
    assert(dr.days.length == 9)
  }

  test("fluent api") {
    val dr = NJDateTimeRange(sydneyTime)
      .withOneDay(LocalDate.now())
      .withOneDay(LocalDate.now().toString)
      .withToday
      .withYesterday
      .withStartTime(1000L)
      .withStartTime("2012-12-30")
      .withStartTime(Instant.now)
      .withStartTime(LocalDate.now())
      .withStartTime(Timestamp.from(Instant.now))
      .withStartTime(LocalTime.now())
      .withStartTime(LocalDateTime.now)
      .withStartTime(ZonedDateTime.now)
      .withStartTime(OffsetDateTime.now)
      .withEndTime(1000L)
      .withEndTime("2012-12-30")
      .withEndTime(Instant.now)
      .withEndTime(LocalDate.now())
      .withEndTime(Timestamp.from(Instant.now))
      .withEndTime(LocalTime.now())
      .withEndTime(LocalDateTime.now)
      .withEndTime(ZonedDateTime.now)
      .withEndTime(OffsetDateTime.now)
      .withZoneId("Australia/Sydney")
      .withZoneId(sydneyTime)
      .withNSeconds(1000)
      .withTimeRange("2020-12-30", "2020-12-31")

    dr.period
    dr.javaDuration
    assert(dr.startTimestamp.isDefined)
    assert(dr.endTimestamp.isDefined)
    assert(dr.zonedStartTime.isDefined)
    assert(dr.zonedEndTime.isDefined)
    assert(dr.duration.isDefined)
    dr.show
  }

  test("show sql date and timestamp") {
    val date = java.sql.Date.valueOf(LocalDate.now)
    val ts   = java.sql.Timestamp.from(Instant.now())
    val utc  = NJTimestamp.now(Clock.systemUTC())
    val nj   = NJTimestamp.now()
    val njt  = NJTimestamp(ts)
    val znj  = NJTimestamp(ZonedDateTime.now())
    val onj2 = NJTimestamp("2020-12-20T23:00:00+11:00")
    val onj  = NJTimestamp(OffsetDateTime.now()).atZone("Australia/Sydney")
    date.show
    ts.show
    nj.show

    assertThrows[Exception](NJTimestamp("abc"))
    assertThrows[Exception](NJTimestamp("abc", sydneyTime))
  }

  test("subranges") {
    val dr = NJDateTimeRange(sydneyTime).withStartTime("2021-01-01").withEndTime("2021-02-01")
    val sr = dr.subranges(24.hours)
    assert(sr.size == 31)
    assert(sr == dr.subranges(1.day))
    val rd = Random.nextInt(30)
    assert(sr(rd).endTimestamp == sr(rd + 1).startTimestamp)
  }
  test("subranges - irregular") {
    val dr = NJDateTimeRange(sydneyTime).withStartTime("2021-01-01").withEndTime("2021-02-01T08:00")
    val sr = dr.subranges(12.hours)
    assert(sr.size == 63)
    sr.sliding(2).toList.map { case List(a, b) =>
      assert(a.endTimestamp == b.startTimestamp)
    }
  }
}
