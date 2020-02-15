package com.github.chenharryhua.nanjin.spark.streaming

import cats.effect.{Concurrent, Timer}
import fs2.Stream
import org.apache.spark.sql.streaming.{DataStreamWriter, OutputMode, StreamingQueryProgress}

final class NJConsoleSink[F[_], A](dsw: DataStreamWriter[A], cfg: StreamConfig)
    extends NJStreamSink[F] {

  override val params: StreamParams = StreamConfigF.evalConfig(cfg)

  override def queryStream(
    implicit F: Concurrent[F],
    timer: Timer[F]): Stream[F, StreamingQueryProgress] =
    ss.queryStream(
      dsw
        .trigger(params.trigger)
        .format("console")
        .outputMode(OutputMode.Append)
        .option("truncate", params.showDs.isTruncate.toString)
        .option("numRows", params.showDs.rowNum.toString)
        .option("failOnDataLoss", params.dataLoss.value))

}
