package com.github.chenharryhua.nanjin.spark.streaming

import cats.effect.{Concurrent, Timer}
import fs2.Stream
import org.apache.spark.sql.streaming.{DataStreamWriter, OutputMode, StreamingQueryProgress}

final case class DatePartitionedCR[K, V](
  Year: String,
  Month: String,
  Day: String,
  partition: Int,
  offset: Long,
  key: Option[K],
  value: Option[V])

final class DatePartitionFileSink[F[_], K, V](
  dsw: DataStreamWriter[DatePartitionedCR[K, V]],
  cfg: StreamConfig,
  path: String)
    extends NJStreamSink[F] {

  override val params: StreamParams = StreamConfigF.evalConfig(cfg)

  override def queryStream(
    implicit F: Concurrent[F],
    timer: Timer[F]): Stream[F, StreamingQueryProgress] =
    ss.queryStream(
      dsw
        .partitionBy("Year", "Month", "Day")
        .trigger(params.trigger)
        .format(params.fileFormat.format)
        .outputMode(OutputMode.Append)
        .option("path", path)
        .option("checkpointLocation", params.checkpoint.value)
        .option("failOnDataLoss", params.dataLoss.value))

}
