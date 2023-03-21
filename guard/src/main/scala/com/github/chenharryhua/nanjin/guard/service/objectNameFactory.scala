package com.github.chenharryhua.nanjin.guard.service

import com.codahale.metrics.jmx.ObjectNameFactory
import com.github.chenharryhua.nanjin.guard.config.MetricID
import io.circe.parser.decode

import java.util
import javax.management.ObjectName

private object objectNameFactory extends ObjectNameFactory {
  override def createName(tipe: String, domain: String, name: String): ObjectName =
    decode[MetricID](name) match {
      case Left(ex) => throw ex
      case Right(mId) =>
        val properties = new util.Hashtable[String, String]()
        properties.put("name", mId.metricName.value)
        properties.put("digest", mId.metricName.digest.value)
        properties.put("category", mId.category.name)
        properties.put("type", tipe)
        new ObjectName(domain, properties)
    }
}
