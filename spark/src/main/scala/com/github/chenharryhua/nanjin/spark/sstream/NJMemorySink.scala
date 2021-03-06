package com.github.chenharryhua.nanjin.spark.sstream

import cats.effect.Async
import com.github.chenharryhua.nanjin.common.utils.random4d
import fs2.Stream
import org.apache.spark.sql.streaming.{DataStreamWriter, StreamingQueryProgress, Trigger}

final class NJMemorySink[F[_], A](dsw: DataStreamWriter[A], cfg: SStreamConfig) extends NJStreamSink[F] {

  override val params: SStreamParams = cfg.evalConfig

  private def updateCfg(f: SStreamConfig => SStreamConfig): NJMemorySink[F, A] =
    new NJMemorySink[F, A](dsw, f(cfg))

  // https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#output-sinks
  def append: NJMemorySink[F, A]   = updateCfg(_.append_mode)
  def complete: NJMemorySink[F, A] = updateCfg(_.complete_mode)

  def trigger(trigger: Trigger): NJMemorySink[F, A] = updateCfg(_.trigger_mode(trigger))

  override def queryStream(implicit F: Async[F]): Stream[F, StreamingQueryProgress] =
    ss.queryStream(
      dsw
        .trigger(params.trigger)
        .format("memory")
        .queryName(params.queryName.getOrElse(s"memory-${random4d.value}"))
        .outputMode(params.outputMode)
        .option("failOnDataLoss", params.dataLoss.value),
      params.progressInterval
    )
}
