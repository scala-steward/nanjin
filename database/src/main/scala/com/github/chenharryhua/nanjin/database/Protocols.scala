package com.github.chenharryhua.nanjin.database

import java.net.URL

import enumeratum.{CatsEnum, Enum, EnumEntry}

import scala.collection.immutable

sealed abstract private[database] class Protocols(val value: String)
    extends EnumEntry with Serializable {

  final def url(host: Host, port: Option[Port]): String =
    port match {
      case None    => s"$value://${host.value}"
      case Some(p) => s"$value://${host.value}:$p"
    }
}

private[database] object Protocols extends Enum[Protocols] with CatsEnum[Protocols] {
  override val values: immutable.IndexedSeq[Protocols] = findValues

  case object MongoDB extends Protocols("mongodb")
  case object Postgres extends Protocols("jdbc:postgresql")
  case object Redshift extends Protocols("jdbc:redshift")
  case object SqlServer extends Protocols("jdbc:sqlserver")
  case object Neo4j extends Protocols("bolt")
  case object Ftp extends Protocols("ftp")
  case object Sftp extends Protocols("sftp")
  case object Ftps extends Protocols("ftps")
  case object S3a extends Protocols("s3a")
  case object S3 extends Protocols("s3")

  type MongoDB   = MongoDB.type
  type Postgres  = Postgres.type
  type Redshift  = Redshift.type
  type SqlServer = SqlServer.type
  type Neo4j     = Neo4j.type
  type Ftp       = Ftp.type
  type Sftp      = Sftp.type
  type Ftps      = Ftps.type
  type S3a       = S3a.type
  type S3        = S3.type
}
