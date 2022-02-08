package com.github.chenharryhua.nanjin.spark.persist

import cats.effect.kernel.Sync
import fs2.{compression, Pipe}
import fs2.compression.DeflateParams
import fs2.compression.DeflateParams.Level
import org.apache.avro.file.{CodecFactory, DataFileConstants}
import org.apache.avro.mapred.AvroOutputFormat
import org.apache.avro.mapreduce.AvroJob
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.parquet.hadoop.metadata.CompressionCodecName

sealed trait NJCompression extends Serializable {
  def name: String

  final def avro(conf: Configuration): CodecFactory = this match {
    case NJCompression.Uncompressed =>
      conf.set(FileOutputFormat.COMPRESS, "false")
      conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.NULL_CODEC)
      CodecFactory.nullCodec()
    case NJCompression.Snappy =>
      conf.set(FileOutputFormat.COMPRESS, "true")
      conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.SNAPPY_CODEC)
      CodecFactory.snappyCodec()
    case NJCompression.Bzip2 =>
      conf.set(FileOutputFormat.COMPRESS, "true")
      conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.BZIP2_CODEC)
      CodecFactory.bzip2Codec()
    case NJCompression.Deflate(v) =>
      conf.set(FileOutputFormat.COMPRESS, "true")
      conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.DEFLATE_CODEC)
      conf.set(AvroOutputFormat.DEFLATE_LEVEL_KEY, v.toString)
      CodecFactory.deflateCodec(v)
    case NJCompression.Xz(v) =>
      conf.set(FileOutputFormat.COMPRESS, "true")
      conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.XZ_CODEC)
      conf.set(AvroOutputFormat.XZ_LEVEL_KEY, v.toString)
      CodecFactory.xzCodec(v)
    case c => throw new Exception(s"not support $c in avro")
  }

  final def parquet: CompressionCodecName = this match {
    case NJCompression.Uncompressed => CompressionCodecName.UNCOMPRESSED
    case NJCompression.Snappy       => CompressionCodecName.SNAPPY
    case NJCompression.Gzip         => CompressionCodecName.GZIP
    case NJCompression.Lz4          => CompressionCodecName.LZ4
    case NJCompression.Lzo          => CompressionCodecName.LZO
    case NJCompression.Brotli       => CompressionCodecName.BROTLI
    case NJCompression.Zstandard(_) => CompressionCodecName.ZSTD
    case c                          => throw new Exception(s"not support $c in parquet")
  }

  final def fs2Compression[F[_]: Sync]: Pipe[F, Byte, Byte] = {
    val cps: compression.Compression[F] = fs2.compression.Compression[F]
    this match {
      case NJCompression.Uncompressed => identity
      case NJCompression.Gzip         => cps.gzip()
      case NJCompression.Deflate(level) =>
        val lvl: Level = level match {
          case 0 => Level.ZERO
          case 1 => Level.ONE
          case 2 => Level.TWO
          case 3 => Level.THREE
          case 4 => Level.FOUR
          case 5 => Level.FIVE
          case 6 => Level.SIX
          case 7 => Level.SEVEN
          case 8 => Level.EIGHT
          case 9 => Level.NINE
        }
        cps.deflate(DeflateParams(level = lvl))
      case c => throw new Exception(s"fs2 Stream does not support codec: $c")
    }
  }
}

object NJCompression {

  case object Uncompressed extends NJCompression {
    override val name = "uncompressed"
  }

  case object Snappy extends NJCompression {
    override val name: String = "snappy"
  }

  case object Bzip2 extends NJCompression {
    override val name: String = "bzip2"
  }

  case object Gzip extends NJCompression {
    override val name: String = "gzip"
  }

  case object Lz4 extends NJCompression {
    override val name: String = "lz4"
  }

  case object Lzo extends NJCompression {
    override val name: String = "lzo"
  }

  case object Brotli extends NJCompression {
    override val name: String = "brotli"
  }

  final case class Deflate(level: Int) extends NJCompression {
    override val name: String = "deflate"
  }

  final case class Xz(level: Int) extends NJCompression {
    override val name: String = "xz"
  }

  final case class Zstandard(level: Int) extends NJCompression {
    override val name: String = "zstd"
  }
}