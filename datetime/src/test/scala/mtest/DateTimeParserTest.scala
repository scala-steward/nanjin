package mtest

import java.time.{LocalDate, LocalTime, ZoneId, ZonedDateTime}

import com.github.chenharryhua.nanjin.datetime.{NJDateTimeRange, NJTimestamp}
import org.scalatest.funsuite.AnyFunSuite

class DateTimeParserTest extends AnyFunSuite {
  val range: NJDateTimeRange = NJDateTimeRange.infinite
  test("Local Date") {
    assert(
      range.withStartTime("2020-01-01").startTimestamp.get === NJTimestamp(
        ZonedDateTime.of(LocalDate.of(2020, 1, 1), LocalTime.MIDNIGHT, ZoneId.systemDefault())))
  }

  test("Local Time") {
    assert(
      range.withStartTime("00:00:00").zonedStartTime.get === ZonedDateTime
        .of(LocalDate.now, LocalTime.MIDNIGHT, ZoneId.systemDefault()))

  }

  test("Local Date Time") {
    assert(
      range.withStartTime("2020-01-01T00:00:00").zonedStartTime.get === ZonedDateTime
        .of(LocalDate.of(2020, 1, 1), LocalTime.MIDNIGHT, ZoneId.systemDefault()))
  }

  test("UTC") {
    val date =
      NJTimestamp(
        ZonedDateTime.of(LocalDate.of(2020, 1, 1), LocalTime.MIDNIGHT, ZoneId.of("Etc/UTC")))
    assert(range.withStartTime("2020-01-01T00:00:00Z").startTimestamp.get === date)
    assert(NJTimestamp("2020-01-01T00:00:00Z") === date)
    assert(NJTimestamp("2020-01-01T11:00+11:00") === date)
    assert(NJTimestamp("2020-01-01T11:00+11:00[Australia/Melbourne]") === date)
  }

  test("Zoned Date Time") {
    val date =
      ZonedDateTime.of(LocalDate.of(2020, 1, 1), LocalTime.MIDNIGHT, ZoneId.systemDefault())
    assert(
      range
        .withStartTime("2020-01-01T00:00+11:00[Australia/Melbourne]")
        .zonedStartTime
        .get === date)

    assert(NJTimestamp("2020-01-01T00:00+11:00[Australia/Melbourne]").local === date)
  }
  test("Offset Date Time") {
    val date =
      ZonedDateTime.of(LocalDate.of(2020, 1, 1), LocalTime.MIDNIGHT, ZoneId.systemDefault())
    assert(range.withStartTime("2020-01-01T00:00+11:00").zonedStartTime.get === date)
    assert(NJTimestamp("2020-01-01T00:00+11:00").local === date)
  }
}
