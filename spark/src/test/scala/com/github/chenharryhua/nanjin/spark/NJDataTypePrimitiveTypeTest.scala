package com.github.chenharryhua.nanjin.spark

import avrohugger.Generator
import avrohugger.format.Standard
import org.apache.spark.sql.avro.SchemaConverters
import org.apache.spark.sql.types._
import org.scalatest.funsuite.AnyFunSuite

object NJDataTypeFTestData {

  val st = StructType(
    List(
      StructField("a", ByteType, true),
      StructField("b", ShortType, true),
      StructField("c", IntegerType, false),
      StructField("d", LongType, false),
      StructField("e", FloatType, false),
      StructField("f", DoubleType, false),
      StructField("g", DecimalType(7, 3), false),
      StructField("h", BooleanType, false),
      StructField("i", BinaryType, false),
      StructField("j", StringType, false),
      StructField("k", TimestampType, false),
      StructField("l", DateType, false)
    )
  )
}

class NJDataTypePrimitiveTypeTest extends AnyFunSuite {
  import NJDataTypeFTestData._
  val generator: Generator = Generator(Standard)
  test("primitive types") {
    val rst = NJDataType(st).toSchema
    println(NJDataType(st).toCaseClass)
    println(rst)
    println(generator.schemaToStrings(rst))
  }
  test("avro-hugger can not translate schema to case class which generated by spark (happy failure)") {
    val spark = SchemaConverters.toAvroType(st, false, "FixMe", "nj.spark")
    assertThrows[Throwable](generator.schemaToStrings(spark))
  }
}
