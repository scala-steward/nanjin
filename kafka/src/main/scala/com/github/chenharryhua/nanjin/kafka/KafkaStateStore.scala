package com.github.chenharryhua.nanjin.kafka

import com.github.chenharryhua.nanjin.messages.kafka.codec.SerdeOf
import com.twitter.algebird.monad.Reader
import org.apache.kafka.streams.kstream.GlobalKTable
import org.apache.kafka.streams.processor.StateStore
import org.apache.kafka.streams.scala.kstream.{Consumed, KTable, Materialized}
import org.apache.kafka.streams.scala.{
  ByteArrayKeyValueStore,
  ByteArraySessionStore,
  ByteArrayWindowStore,
  StreamsBuilder
}
import org.apache.kafka.streams.state.{KeyValueBytesStoreSupplier, SessionBytesStoreSupplier, WindowBytesStoreSupplier}

final class KafkaStateStore[K, V](storeName: StoreName)(implicit keySerde: SerdeOf[K], valSerde: SerdeOf[V]) {

  def materialized[S <: StateStore]: Materialized[K, V, S] =
    Materialized.as[K, V, S](storeName.value)(keySerde, valSerde)

  def materialized(supplier: WindowBytesStoreSupplier): Materialized[K, V, ByteArrayWindowStore] =
    Materialized.as[K, V](supplier)(keySerde, valSerde)

  def materialized(supplier: SessionBytesStoreSupplier): Materialized[K, V, ByteArraySessionStore] =
    Materialized.as[K, V](supplier)(keySerde, valSerde)

  def materialized(supplier: KeyValueBytesStoreSupplier): Materialized[K, V, ByteArrayKeyValueStore] =
    Materialized.as[K, V](supplier)(keySerde, valSerde)

  def table[S <: StateStore]: Reader[StreamsBuilder, KTable[K, V]] =
    Reader[StreamsBuilder, KTable[K, V]](
      _.table[K, V](storeName.value, materialized)(Consumed.`with`(keySerde, valSerde)))

  def table(supplier: KeyValueBytesStoreSupplier): Reader[StreamsBuilder, KTable[K, V]] =
    Reader[StreamsBuilder, KTable[K, V]](
      _.table[K, V](storeName.value, materialized(supplier))(Consumed.`with`(keySerde, valSerde)))

  def gtable: Reader[StreamsBuilder, GlobalKTable[K, V]] =
    Reader[StreamsBuilder, GlobalKTable[K, V]](_.globalTable(storeName.value)(Consumed.`with`(keySerde, valSerde)))

  def gtable(supplier: KeyValueBytesStoreSupplier): Reader[StreamsBuilder, GlobalKTable[K, V]] =
    Reader[StreamsBuilder, GlobalKTable[K, V]](
      _.globalTable(storeName.value, materialized(supplier))(Consumed.`with`(keySerde, valSerde)))
}
