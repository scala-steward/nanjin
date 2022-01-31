package com.github.chenharryhua.nanjin.terminals

import akka.{Done, NotUsed}
import akka.stream.{ActorAttributes, Attributes, IOResult, Inlet, Outlet, SinkShape, SourceShape}
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.stream.stage.{GraphStage, GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import akka.util.ByteString
import org.apache.avro.Schema
import org.apache.avro.file.{CodecFactory, DataFileStream, DataFileWriter}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FSDataOutputStream, FileSystem}
import squants.information.Information

import java.io.{InputStream, OutputStream}
import java.net.URI
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

final class AkkaHadoop(config: Configuration) {
  private def fileSystem(uri: URI): FileSystem           = FileSystem.get(uri, config)
  private def fsOutput(path: NJPath): FSDataOutputStream = fileSystem(path.uri).create(path.hadoopPath)
  private def fsInput(path: NJPath): FSDataInputStream   = fileSystem(path.uri).open(path.hadoopPath)

  def byteSource(path: NJPath, byteBuffer: Information): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => fsInput(path), byteBuffer.toBytes.toInt)
  def byteSource(path: NJPath): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => fsInput(path))

  def byteSink(path: NJPath): Sink[ByteString, Future[IOResult]] =
    StreamConverters.fromOutputStream(() => fsOutput(path))

  def avroSource(path: NJPath, schema: Schema): Source[GenericRecord, NotUsed] =
    Source.fromGraph(new AvroSource(fsInput(path), schema))
  def avroSink(path: NJPath, schema: Schema, codecFactory: CodecFactory): Sink[GenericRecord, Future[Done]] =
    Sink.fromGraph(new AvroSink(fsOutput(path), schema, codecFactory))
}

private class AvroSource(is: InputStream, schema: Schema) extends GraphStage[SourceShape[GenericRecord]] {
  private val out: Outlet[GenericRecord] = Outlet("akka.avro.source")

  override protected val initialAttributes: Attributes = super.initialAttributes.and(ActorAttributes.IODispatcher)

  private val reader: DataFileStream[GenericRecord] =
    new DataFileStream(is, new GenericDatumReader[GenericRecord](schema))

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(
        out,
        new OutHandler {
          override def onDownstreamFinish(cause: Throwable): Unit =
            try {
              reader.close()
              is.close()
            } finally super.onDownstreamFinish(cause)

          override def onPull(): Unit =
            if (reader.hasNext) push(out, reader.next())
            else complete(out)
        }
      )
    }
  override val shape: SourceShape[GenericRecord] = SourceShape.of(out)
}

private class AvroSink(os: OutputStream, schema: Schema, codecFactory: CodecFactory)
    extends GraphStageWithMaterializedValue[SinkShape[GenericRecord], Future[Done]] {

  private val in: Inlet[GenericRecord] = Inlet("akka.avro.sink")

  private val writer: DataFileWriter[GenericRecord] =
    new DataFileWriter(new GenericDatumWriter[GenericRecord](schema, GenericData.get()))
      .setCodec(codecFactory)
      .create(schema, os)

  override val shape: SinkShape[GenericRecord] = SinkShape.of(in)

  override protected val initialAttributes: Attributes = super.initialAttributes.and(ActorAttributes.IODispatcher)

  override def createLogicAndMaterializedValue(attr: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]()
    val logic = new GraphStageLogic(shape) {
      setHandler(
        in,
        new InHandler {
          override def onUpstreamFinish(): Unit =
            try {
              writer.close()
              os.close()
              promise.complete(Success(Done))
            } catch {
              case ex: Throwable => promise.complete(Failure(ex))
            } finally super.onUpstreamFinish()

          override def onUpstreamFailure(ex: Throwable): Unit =
            try {
              writer.close()
              os.close()
            } finally {
              super.onUpstreamFailure(ex)
              promise.complete(Failure(ex))
            }

          override def onPush(): Unit = {
            val gr: GenericRecord = grab(in)
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
