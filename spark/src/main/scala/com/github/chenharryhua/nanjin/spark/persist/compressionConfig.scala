package com.github.chenharryhua.nanjin.spark.persist

import com.github.chenharryhua.nanjin.common.NJCompression
import io.scalaland.enumz.Enum
import org.apache.avro.file.DataFileConstants
import org.apache.avro.mapred.AvroOutputFormat
import org.apache.avro.mapreduce.AvroJob
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.*
import org.apache.hadoop.io.compress.zlib.ZlibCompressor.CompressionLevel
import org.apache.hadoop.io.compress.zlib.ZlibFactory
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.parquet.hadoop.codec.ZstandardCodec
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.spark.sql.catalyst.util.CompressionCodecs

private[persist] object compressionConfig {

  final def avro(conf: Configuration, compression: NJCompression): Unit =
    compression match {
      case NJCompression.Uncompressed =>
        conf.set(FileOutputFormat.COMPRESS, "false")
        conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.NULL_CODEC)
      case NJCompression.Snappy =>
        conf.set(FileOutputFormat.COMPRESS, "true")
        conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.SNAPPY_CODEC)
      case NJCompression.Bzip2 =>
        conf.set(FileOutputFormat.COMPRESS, "true")
        conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.BZIP2_CODEC)
      case NJCompression.Deflate(v) =>
        conf.set(FileOutputFormat.COMPRESS, "true")
        conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.DEFLATE_CODEC)
        conf.set(AvroOutputFormat.DEFLATE_LEVEL_KEY, v.toString)
      case NJCompression.Xz(v) =>
        conf.set(FileOutputFormat.COMPRESS, "true")
        conf.set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.XZ_CODEC)
        conf.set(AvroOutputFormat.XZ_LEVEL_KEY, v.toString)
      case c => sys.error(s"not support $c in avro")
    }

  final def parquet(conf: Configuration, compression: NJCompression): CompressionCodecName =
    compression match {
      case NJCompression.Uncompressed => CompressionCodecName.UNCOMPRESSED
      case NJCompression.Snappy       => CompressionCodecName.SNAPPY
      case NJCompression.Gzip         => CompressionCodecName.GZIP
      case NJCompression.Lz4          => CompressionCodecName.LZ4
      case NJCompression.Brotli       => CompressionCodecName.BROTLI
      case NJCompression.Zstandard(level) =>
        conf.set(ZstandardCodec.PARQUET_COMPRESS_ZSTD_LEVEL, level.toString)
        CompressionCodecName.ZSTD
      case c => sys.error(s"not support $c in parquet")
    }

  final def set(config: Configuration, compression: NJCompression): Unit = {
    val ecl: Enum[CompressionLevel] = Enum[CompressionLevel]
    compression match {
      case NJCompression.Uncompressed => CompressionCodecs.setCodecConfiguration(config, null)
      case NJCompression.Snappy       => CompressionCodecs.setCodecConfiguration(config, classOf[SnappyCodec].getName)
      case NJCompression.Bzip2        => CompressionCodecs.setCodecConfiguration(config, classOf[BZip2Codec].getName)
      case NJCompression.Gzip         => CompressionCodecs.setCodecConfiguration(config, classOf[GzipCodec].getName)
      case NJCompression.Lz4          => CompressionCodecs.setCodecConfiguration(config, classOf[Lz4Codec].getName)
      case NJCompression.Deflate(level) =>
        ZlibFactory.setCompressionLevel(config, ecl.withIndex(level))
        CompressionCodecs.setCodecConfiguration(config, classOf[DeflateCodec].getName)
      case NJCompression.Zstandard(level) =>
        ZlibFactory.setCompressionLevel(config, ecl.withIndex(level))
        CompressionCodecs.setCodecConfiguration(config, classOf[ZStandardCodec].getName)
      case cc => sys.error(s"${cc.shortName} is not supported")
    }
  }
}
