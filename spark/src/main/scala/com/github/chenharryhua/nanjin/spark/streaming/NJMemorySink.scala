package com.github.chenharryhua.nanjin.spark.streaming

import cats.effect.{Concurrent, Timer}
import fs2.Stream
import org.apache.spark.sql.streaming.{DataStreamWriter, StreamingQueryProgress}

final class NJMemorySink[F[_], A](dsw: DataStreamWriter[A], cfg: NJStreamConfig, queryName: String)
    extends NJStreamSink[F] {

  override val params: NJStreamParams = cfg.evalConfig

  override def queryStream(implicit
    F: Concurrent[F],
    timer: Timer[F]): Stream[F, StreamingQueryProgress] =
    ss.queryStream(
      dsw
        .format("memory")
        .queryName(queryName)
        .outputMode(params.outputMode)
        .option("failOnDataLoss", params.dataLoss.value))

}
