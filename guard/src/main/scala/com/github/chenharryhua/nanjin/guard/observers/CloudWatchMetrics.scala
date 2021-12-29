package com.github.chenharryhua.nanjin.guard.observers

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all.*
import com.amazonaws.services.cloudwatch.model.*
import com.github.chenharryhua.nanjin.aws.CloudWatch
import com.github.chenharryhua.nanjin.guard.event.{MetricsReport, NJEvent}
import fs2.{Pipe, Pull, Stream}

import java.time.ZonedDateTime
import java.util.{Date, UUID}
import scala.jdk.CollectionConverters.*

object cloudwatch {
  def apply[F[_]: Sync](client: Resource[F, CloudWatch[F]], namespace: String): CloudWatchMetrics[F] =
    new CloudWatchMetrics[F](client, namespace, 60)

  def apply[F[_]: Sync](namespace: String): CloudWatchMetrics[F] =
    apply[F](CloudWatch[F], namespace)
}

final private case class MetricKey(
  uuid: UUID,
  hostName: String,
  standardUnit: StandardUnit,
  task: String,
  service: String,
  metricName: String) {
  def metricDatum(ts: ZonedDateTime, count: Long): MetricDatum =
    new MetricDatum()
      .withDimensions(
        new Dimension().withName("Task").withValue(task),
        new Dimension().withName("Service").withValue(service),
        new Dimension().withName("Host").withValue(hostName)
      )
      .withMetricName(metricName)
      .withUnit(standardUnit)
      .withTimestamp(Date.from(ts.toInstant))
      .withValue(count)
}

final class CloudWatchMetrics[F[_]] private[observers] (
  client: Resource[F, CloudWatch[F]],
  namespace: String,
  storageResolution: Int)(implicit F: Sync[F])
    extends Pipe[F, NJEvent, NJEvent] {

  def withStorageResolution(storageResolution: Int): CloudWatchMetrics[F] = {
    require(
      storageResolution > 0 && storageResolution <= 60,
      s"storageResolution($storageResolution) should be between 1 and 60 inclusively")
    new CloudWatchMetrics(client, namespace, storageResolution)
  }

  private def buildMetricDatum(
    report: MetricsReport,
    last: Map[MetricKey, Long]): (List[MetricDatum], Map[MetricKey, Long]) = {
    val counterAndMeter: Map[String, Long] = report.snapshot.counterCount ++ report.snapshot.meterCount

    val keyMap: Map[MetricKey, Long] = counterAndMeter.map { case (metricName, counter) =>
      MetricKey(
        report.serviceInfo.uuid,
        report.serviceInfo.serviceParams.taskParams.hostName,
        StandardUnit.Count,
        report.serviceInfo.serviceParams.taskParams.appName,
        report.serviceInfo.serviceParams.serviceName,
        metricName
      ) -> counter
    }

    keyMap.foldLeft((List.empty[MetricDatum], last)) { case ((mds, last), (key, count)) =>
      last.get(key) match {
        case Some(old) =>
          if (count > old) (key.metricDatum(report.timestamp, count - old) :: mds, last.updated(key, count))
          else if (count === old) (mds, last)
          else (key.metricDatum(report.timestamp, count) :: mds, last.updated(key, count))
        case None =>
          (key.metricDatum(report.timestamp, count) :: mds, last.updated(key, count))
      }
    }
  }

  override def apply(es: Stream[F, NJEvent]): Stream[F, NJEvent] = {
    def go(cw: CloudWatch[F], ss: Stream[F, NJEvent], last: Map[MetricKey, Long]): Pull[F, NJEvent, Unit] =
      ss.pull.uncons.flatMap {
        case Some((events, tail)) =>
          val (mds, next) = events.collect { case mr: MetricsReport => mr }.foldLeft((List.empty[MetricDatum], last)) {
            case ((lmd, last), mr) =>
              val (mds, newLast) = buildMetricDatum(mr, last)
              (mds ::: lmd, newLast)
          }

          val publish: F[List[Either[Throwable, PutMetricDataResult]]] =
            mds // https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html
              .grouped(20)
              .toList
              .traverse(ds =>
                cw.putMetricData(
                  new PutMetricDataRequest()
                    .withNamespace(namespace)
                    .withMetricData(ds.map(_.withStorageResolution(storageResolution)).asJava))
                  .attempt)

          Pull.eval(publish) >> Pull.output(events) >> go(cw, tail, next)

        case None => Pull.done
      }

    Stream.resource(client).flatMap(cw => go(cw, es, Map.empty).stream)
  }
}
