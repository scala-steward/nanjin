package mtest

import java.time.{Instant, LocalDate, LocalDateTime, ZoneId}

import com.fortysevendeg.scalacheck.datetime.jdk8.ArbitraryJdk8._
import com.github.chenharryhua.nanjin.spark._
import org.scalacheck.Prop.{forAll, propBoolean}
import org.scalacheck.Properties

class TimeInjectionProps extends Properties("Injection") {
  implicit val zoneId = ZoneId.systemDefault()

  property("Instant identity") = forAll { (dt: Instant) =>
    instantInjection.invert(instantInjection(dt)) == dt
  }

  property("LocalDateTime identity") = forAll { (dt: LocalDateTime) =>
    localDateTimeInjection.invert(localDateTimeInjection.apply(dt)) == dt
  }

  property("ZonedDateTime identity") = forAll { (dt: Instant) =>
    zonedDateTimeInjection.apply(zonedDateTimeInjection.invert((dt))) == dt
  }

  // not exactly isomorphic. bad news for archeology
  property("localDate identity") = forAll { (dt: LocalDate) =>
    (dt.getYear > -100000L) ==> (localDateInjection.invert(localDateInjection(dt)) == dt)
  }
}
