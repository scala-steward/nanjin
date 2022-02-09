package com.github.chenharryhua.nanjin.terminals

import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage.{
  GraphStageLogic,
  GraphStageLogicWithLogging,
  GraphStageWithMaterializedValue,
  InHandler,
  OutHandler
}
import akka.stream.{
  ActorAttributes,
  Attributes,
  IOOperationIncompleteException,
  IOResult,
  Inlet,
  Outlet,
  SinkShape,
  SourceShape,
  SubscriptionWithCancelException
}
import cats.effect.kernel.Sync
import com.github.chenharryhua.nanjin.common.ChunkSize
import fs2.{Pipe, Pull, Stream}
import org.apache.avro.Schema
import org.apache.avro.file.{CodecFactory, DataFileStream, DataFileWriter}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.hadoop.util.HadoopOutputFile

import java.io.{InputStream, OutputStream}
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*

final class NJAvro[F[_]] private (
  cfg: Configuration,
  schema: Schema,
  codecFactory: CodecFactory,
  blockSizeHint: Long,
  chunkSize: ChunkSize)(implicit F: Sync[F]) {
  def withCodecFactory(codecFactory: CodecFactory): NJAvro[F] =
    new NJAvro[F](cfg, schema, codecFactory, blockSizeHint, chunkSize)
  def withChunSize(cs: ChunkSize): NJAvro[F]   = new NJAvro[F](cfg, schema, codecFactory, blockSizeHint, cs)
  def withBlockSizeHint(size: Long): NJAvro[F] = new NJAvro[F](cfg, schema, codecFactory, size, chunkSize)

  def sink(path: NJPath): Pipe[F, GenericRecord, Unit] = {
    def go(grs: Stream[F, GenericRecord], writer: DataFileWriter[GenericRecord]): Pull[F, Unit, Unit] =
      grs.pull.uncons.flatMap {
        case Some((hl, tl)) => Pull.eval(F.blocking(hl.foreach(writer.append))) >> go(tl, writer)
        case None           => Pull.eval(F.blocking(writer.close())) >> Pull.done
      }

    val output: HadoopOutputFile = path.hadoopOutputFile(cfg)

    (ss: Stream[F, GenericRecord]) =>
      for {
        dfw <- Stream.bracket[F, DataFileWriter[GenericRecord]](
          F.blocking(new DataFileWriter(new GenericDatumWriter(schema)).setCodec(codecFactory)))(r =>
          F.blocking(r.close()))
        os <- Stream.bracket(F.blocking(output.createOrOverwrite(blockSizeHint)))(r => F.blocking(r.close()))
        writer <- Stream.bracket(F.blocking(dfw.create(schema, os)))(r => F.blocking(r.close()))
        _ <- go(ss, writer).stream
      } yield ()
  }

  def source(path: NJPath): Stream[F, GenericRecord] =
    for {
      is <- Stream.bracket(F.blocking(path.hadoopInputFile(cfg).newStream()))(r => F.blocking(r.close()))
      dfs <- Stream.bracket[F, DataFileStream[GenericRecord]](
        F.blocking(new DataFileStream(is, new GenericDatumReader(schema))))(r => F.blocking(r.close()))
      gr <- Stream.fromBlockingIterator(dfs.iterator().asScala, chunkSize.value)
    } yield gr

  object akka {
    def source(path: NJPath): Source[GenericRecord, Future[IOResult]] =
      Source.fromGraph(new AkkaAvroSource(path.hadoopInputFile(cfg).newStream(), schema))

    def sink(path: NJPath): Sink[GenericRecord, Future[IOResult]] =
      Sink.fromGraph(
        new AkkaAvroSink(path.hadoopOutputFile(cfg).createOrOverwrite(blockSizeHint), schema, codecFactory))
  }
}

object NJAvro {
  def apply[F[_]: Sync](schema: Schema, cfg: Configuration): NJAvro[F] =
    new NJAvro[F](cfg, schema, CodecFactory.nullCodec(), -1L, ChunkSize(1000))
}

private class AkkaAvroSource(is: InputStream, schema: Schema)
    extends GraphStageWithMaterializedValue[SourceShape[GenericRecord], Future[IOResult]] {
  private val out: Outlet[GenericRecord] = Outlet("akka.avro.source")

  override protected val initialAttributes: Attributes = super.initialAttributes.and(ActorAttributes.IODispatcher)

  override def createLogicAndMaterializedValue(attr: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val promise: Promise[IOResult] = Promise[IOResult]()
    val logic = new GraphStageLogicWithLogging(shape) {
      override protected val logSource: Class[AkkaAvroSource] = classOf[AkkaAvroSource]
      setHandler(
        out,
        new OutHandler {
          private var count: Long = 0

          private val reader: DataFileStream[GenericRecord] =
            new DataFileStream(is, new GenericDatumReader[GenericRecord](schema))

          override def onDownstreamFinish(cause: Throwable): Unit =
            try {
              super.onDownstreamFinish(cause)
              reader.close()
              is.close()
              cause match {
                case _: SubscriptionWithCancelException.NonFailureCancellation =>
                  promise.success(IOResult(count))
                case ex: Throwable =>
                  promise.failure(new IOOperationIncompleteException("avro.source", count, ex))
              }
            } catch {
              case ex: Throwable => promise.failure(ex)
            }

          override def onPull(): Unit =
            if (reader.hasNext) {
              count += 1
              push(out, reader.next())
            } else complete(out)
        }
      )
    }
    (logic, promise.future)
  }

  override val shape: SourceShape[GenericRecord] = SourceShape.of(out)
}

private class AkkaAvroSink(os: OutputStream, schema: Schema, codecFactory: CodecFactory)
    extends GraphStageWithMaterializedValue[SinkShape[GenericRecord], Future[IOResult]] {

  private val in: Inlet[GenericRecord] = Inlet("akka.avro.sink")

  override val shape: SinkShape[GenericRecord] = SinkShape.of(in)

  override protected val initialAttributes: Attributes = super.initialAttributes.and(ActorAttributes.IODispatcher)

  override def createLogicAndMaterializedValue(attr: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val promise: Promise[IOResult] = Promise[IOResult]()
    val logic = new GraphStageLogicWithLogging(shape) {
      override protected val logSource: Class[AkkaAvroSink] = classOf[AkkaAvroSink]

      setHandler(
        in,
        new InHandler {
          private var count: Long = 0

          private val writer: DataFileWriter[GenericRecord] =
            new DataFileWriter(new GenericDatumWriter[GenericRecord](schema, GenericData.get()))
              .setCodec(codecFactory)
              .create(schema, os)

          override def onUpstreamFinish(): Unit =
            try {
              super.onUpstreamFinish()
              writer.close()
              os.close()
              promise.success(IOResult(count))
            } catch {
              case ex: Throwable => promise.failure(ex)
            }

          override def onUpstreamFailure(ex: Throwable): Unit =
            try {
              super.onUpstreamFailure(ex)
              writer.close()
              os.close()
              promise.failure(new IOOperationIncompleteException("avro.sink", count, ex))
            } catch {
              case ex: Throwable => promise.failure(ex)
            }

          override def onPush(): Unit = {
            val gr: GenericRecord = grab(in)
            count += 1
            writer.append(gr)
            pull(in)
          }
        }
      )
      override def preStart(): Unit = pull(in)
    }
    (logic, promise.future)
  }
}
