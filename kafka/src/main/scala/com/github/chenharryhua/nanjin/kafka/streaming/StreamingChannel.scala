package com.github.chenharryhua.nanjin.kafka.streaming

import cats.data.Reader
import org.apache.kafka.streams.kstream.GlobalKTable
import org.apache.kafka.streams.scala.ByteArrayKeyValueStore
import org.apache.kafka.streams.scala.kstream.Materialized
import com.github.chenharryhua.nanjin.kafka.TopicDef

final class StreamingChannel[K, V] private[kafka] (topicDef: TopicDef[K, V]) {
  import org.apache.kafka.streams.scala.StreamsBuilder
  import org.apache.kafka.streams.scala.kstream.{KStream, KTable}

  val kstream: Reader[StreamsBuilder, KStream[K, V]] =
    Reader(builder => builder.stream[K, V](topicDef.topicName.value)(topicDef.consumed))

  val ktable: Reader[StreamsBuilder, KTable[K, V]] =
    Reader(builder => builder.table[K, V](topicDef.topicName.value)(topicDef.consumed))

  def ktable(mat: Materialized[K, V, ByteArrayKeyValueStore]): Reader[StreamsBuilder, KTable[K, V]] =
    Reader(builder => builder.table[K, V](topicDef.topicName.value, mat)(topicDef.consumed))

  val gktable: Reader[StreamsBuilder, GlobalKTable[K, V]] =
    Reader(builder => builder.globalTable[K, V](topicDef.topicName.value)(topicDef.consumed))

  def gktable(mat: Materialized[K, V, ByteArrayKeyValueStore]): Reader[StreamsBuilder, GlobalKTable[K, V]] =
    Reader(builder => builder.globalTable[K, V](topicDef.topicName.value, mat)(topicDef.consumed))
}
