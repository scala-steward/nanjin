package com.github.chenharryhua.nanjin.spark

import java.sql.{Date, Timestamp}

import frameless.{Injection, SQLDate, SQLTimestamp}
import monocle.Iso
import org.apache.spark.sql.catalyst.util.DateTimeUtils

private[spark] trait InjectionInstances extends Serializable {

  implicit val javaSQLTimestampInjection: Injection[Timestamp, SQLTimestamp] =
    Injection[Timestamp, SQLTimestamp](
      a => SQLTimestamp(DateTimeUtils.fromJavaTimestamp(a)),
      b => DateTimeUtils.toJavaTimestamp(b.us))

  implicit val javaSQLDateInjection: Injection[Date, SQLDate] =
    Injection[Date, SQLDate](
      a => SQLDate(DateTimeUtils.fromJavaDate(a)),
      b => DateTimeUtils.toJavaDate(b.days))

  implicit def isoInjection[A, B](implicit iso: Iso[A, B]): Injection[A, B] =
    Injection[A, B](iso.get, iso.reverseGet)
}
