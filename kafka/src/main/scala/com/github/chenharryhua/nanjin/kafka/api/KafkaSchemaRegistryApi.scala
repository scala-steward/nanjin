package com.github.chenharryhua.nanjin.kafka.api

import avrohugger.Generator
import avrohugger.format.Standard
import avrohugger.types._
import cats.Show
import cats.effect.Sync
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.{KafkaTopic, SchemaRegistrySettings}
import io.confluent.kafka.schemaregistry.client.SchemaMetadata
import org.apache.avro.Schema

import scala.collection.JavaConverters._
import scala.util.Try

final case class KvSchemaMetadata(key: Option[SchemaMetadata], value: Option[SchemaMetadata]) {

  private val scalaTypes: Option[AvroScalaTypes] = Some(
    Standard.defaultTypes.copy(
      record   = ScalaCaseClass,
      array    = ScalaList,
      enum     = ScalaCaseObjectEnum,
      protocol = ScalaADT))

  private def genCaseClass(s: SchemaMetadata): Option[String] =
    Try(
      Generator(Standard, avroScalaCustomTypes = scalaTypes)
        .stringToStrings(s.getSchema)
        .mkString("\n")).toEither.toOption

  def showKey: String =
    s"""|key schema:
        |id:      ${key.map(_.getId).getOrElse("none")}
        |version: ${key.map(_.getVersion).getOrElse("none")}
        |schema:  ${key.map(_.getSchema).getOrElse("none")}
        |scala:   ${key.flatMap(genCaseClass).getOrElse("none")}""".stripMargin

  def showValue: String =
    s"""|value schema:
        |id:      ${value.map(_.getId).getOrElse("none")}
        |version: ${value.map(_.getVersion).getOrElse("none")}
        |schema:  ${value.map(_.getSchema).getOrElse("none")}
        |scala:   ${value.flatMap(genCaseClass).getOrElse("none")}""".stripMargin

  def show: String =
    s"""|key and value schema: 
        |$showKey
        |$showValue
       """.stripMargin

  override def toString: String = show
}

object KvSchemaMetadata {
  implicit val showKvSchemaMetadata: Show[KvSchemaMetadata] = _.show
}

final case class CompatibilityTestReport(
  topicName: String,
  srSettings: SchemaRegistrySettings,
  meta: KvSchemaMetadata,
  keySchema: Schema,
  valueSchema: Schema,
  key: Either[String, Boolean],
  value: Either[String, Boolean]) {

  private val keyDescription: String = key.fold(
    identity,
    if (_) "compatible"
    else
      s"""|incompatible:
          |application:  $keySchema
          |server:       ${meta.key.map(_.getSchema).getOrElse("none")}
          |""".stripMargin
  )

  private val valueDescription: String = value.fold(
    identity,
    if (_) "compatible"
    else
      s"""|incompatible:
          |application:   $valueSchema
          |server:        ${meta.value.map(_.getSchema).getOrElse("none")}
          |""".stripMargin
  )

  val show: String =
    s"""
       |compatibility test report of topic($topicName):
       |key:   $keyDescription
       |
       |value: $valueDescription
       |$srSettings""".stripMargin

  override val toString: String = show

  val isCompatible: Boolean =
    key.flatMap(k => value.map(v => k && v)).fold(_ => false, identity)
}

trait KafkaSchemaRegistryApi[F[_]] extends Serializable {
  def delete: F[(List[Integer], List[Integer])]
  def register: F[(Option[Int], Option[Int])]
  def latestMeta: F[KvSchemaMetadata]
  def testCompatibility: F[CompatibilityTestReport]
}

private[kafka] object KafkaSchemaRegistryApi {

  def apply[F[_]: Sync](topic: KafkaTopic[F, _, _]): KafkaSchemaRegistryApi[F] =
    new KafkaSchemaRegistryImpl(topic)

  final private class KafkaSchemaRegistryImpl[F[_]: Sync](topic: KafkaTopic[F, _, _])
      extends KafkaSchemaRegistryApi[F] {

    val srSettings: SchemaRegistrySettings = topic.context.settings.schemaRegistrySettings
    val topicName: String                  = topic.topicDef.topicName
    val keySchemaLoc: String               = topic.topicDef.keySchemaLoc
    val valueSchemaLoc: String             = topic.topicDef.valueSchemaLoc
    val keySchema: Schema                  = topic.codec.keySchema
    val valueSchema: Schema                = topic.codec.valueSchema

    override def delete: F[(List[Integer], List[Integer])] = {
      val deleteKey =
        Sync[F]
          .delay(srSettings.csrClient.value.deleteSubject(keySchemaLoc).asScala.toList)
          .handleError(_ => Nil)
      val deleteValue =
        Sync[F]
          .delay(
            srSettings.csrClient.value.deleteSubject(valueSchemaLoc).asScala.toList
          )
          .handleError(_ => Nil)
      (deleteKey, deleteValue).mapN((_, _))
    }

    override def register: F[(Option[Int], Option[Int])] =
      (
        Sync[F]
          .delay(srSettings.csrClient.value.register(keySchemaLoc, keySchema))
          .attempt
          .map(_.toOption),
        Sync[F]
          .delay(srSettings.csrClient.value.register(valueSchemaLoc, valueSchema))
          .attempt
          .map(_.toOption)
      ).mapN((_, _))

    override def testCompatibility: F[CompatibilityTestReport] =
      (
        Sync[F]
          .delay(
            srSettings.csrClient.value.testCompatibility(keySchemaLoc, keySchema)
          )
          .attempt
          .map(_.leftMap(_.getMessage)),
        Sync[F]
          .delay(
            srSettings.csrClient.value.testCompatibility(valueSchemaLoc, valueSchema)
          )
          .attempt
          .map(_.leftMap(_.getMessage)),
        latestMeta
      ).mapN((k, v, meta) =>
        CompatibilityTestReport(topicName, srSettings, meta, keySchema, valueSchema, k, v))

    override def latestMeta: F[KvSchemaMetadata] =
      (
        Sync[F]
          .delay(srSettings.csrClient.value.getLatestSchemaMetadata(keySchemaLoc))
          .attempt
          .map(_.toOption),
        Sync[F]
          .delay(srSettings.csrClient.value.getLatestSchemaMetadata(valueSchemaLoc))
          .attempt
          .map(_.toOption)
      ).mapN(KvSchemaMetadata(_, _))
  }
}
