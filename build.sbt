ThisBuild / scalaVersion       := "2.13.8"
ThisBuild / parallelExecution  := false
Global / cancelable            := true
ThisBuild / evictionErrorLevel := Level.Info
ThisBuild / version            := "0.15.5-SNAPSHOT"
ThisBuild / versionScheme      := Some("early-semver")

val algebra      = "2.7.0"
val catsCore     = "2.7.0"
val monocle      = "2.1.0"
val catsEffect   = "3.3.12"
val akka26       = "2.6.19"
val confluent    = "7.1.1"
val kafkaVersion = "7.1.1-ce"
val avro         = "1.11.0"

lazy val commonSettings = Seq(
  organization := "com.github.chenharryhua",
  scalaVersion := scalaVersion.value,
  resolvers ++= Seq(
    Resolver.sonatypeRepo("public"),
    Resolver.sonatypeRepo("releases"),
    "Confluent Maven Repo".at("https://packages.confluent.io/maven/")
  ),
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.2").cross(CrossVersion.full)),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect"  % scalaVersion.value % Provided,
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % Provided
  ),
  scalacOptions ++= Seq(
    "-Ymacro-annotations",
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint:-byname-implicit",
    "-Yrangepos",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xsource:3"
  ),
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.ScalaLibrary
)

val awsLib = Seq("com.amazonaws" % "aws-java-sdk-bundle" % "1.11.1034")

val hadoopLib = Seq(
  "org.apache.hadoop" % "hadoop-mapreduce-client-core",
  "org.apache.hadoop" % "hadoop-aws",
  "org.apache.hadoop" % "hadoop-auth",
  "org.apache.hadoop" % "hadoop-annotations",
  "org.apache.hadoop" % "hadoop-common",
  "org.apache.hadoop" % "hadoop-client",
  "org.apache.hadoop" % "hadoop-hdfs"
).map(_ % "3.3.3") ++ awsLib

val circeLib = Seq(
  "io.circe" %% "circe-literal"        % "0.14.2",
  "io.circe" %% "circe-core"           % "0.14.2",
  "io.circe" %% "circe-generic"        % "0.14.2",
  "io.circe" %% "circe-parser"         % "0.14.2",
  "io.circe" %% "circe-shapes"         % "0.14.2",
  "io.circe" %% "circe-jawn"           % "0.14.2",
  "io.circe" %% "circe-optics"         % "0.14.1",
  "io.circe" %% "circe-jackson210"     % "0.14.0",
  "io.circe" %% "circe-generic-extras" % "0.14.1",
  "io.circe" %% "circe-refined"        % "0.14.2",
  "org.gnieh" %% "diffson-circe"       % "4.1.1"
)

val jacksonLib = Seq(
  "com.fasterxml.jackson.core"                             % "jackson-annotations"             % "2.13.3",
  "com.fasterxml.jackson.core"                             % "jackson-core"                    % "2.13.3",
  "com.fasterxml.jackson.core"                             % "jackson-databind"                % "2.13.3",
  "com.fasterxml.jackson.datatype"                         % "jackson-datatype-jdk8"           % "2.13.3",
  "com.fasterxml.jackson.module"                           % "jackson-module-jaxb-annotations" % "2.13.3",
  "com.fasterxml.jackson.jaxrs"                            % "jackson-jaxrs-base"              % "2.13.3",
  "com.fasterxml.jackson.jaxrs"                            % "jackson-jaxrs-json-provider"     % "2.13.3",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.3"
)

val kantanLib = Seq(
  "com.nrinaudo" %% "kantan.csv",
  "com.nrinaudo" %% "kantan.csv-java8",
  "com.nrinaudo" %% "kantan.csv-generic",
  "com.nrinaudo" %% "kantan.csv-cats"
).map(_ % "0.6.2") ++ Seq("com.nrinaudo" %% "kantan.codecs" % "0.5.3")

val pbLib = Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.10",
  "com.google.protobuf"                       % "protobuf-java"             % "3.20.1",
  "com.google.protobuf"                       % "protobuf-java-util"        % "3.20.1",
  "io.confluent"                              % "kafka-protobuf-serializer" % confluent
)

val serdeLib = Seq(
  "com.sksamuel.avro4s" %% "avro4s-core" % "4.0.13",
  "org.apache.parquet"                   % "parquet-common"           % "1.12.2",
  "org.apache.parquet"                   % "parquet-hadoop"           % "1.12.2",
  "org.apache.parquet"                   % "parquet-avro"             % "1.12.2",
  "org.apache.avro"                      % "avro"                     % avro,
  "io.confluent"                         % "kafka-streams-avro-serde" % confluent
) ++ jacksonLib ++ circeLib ++ pbLib

val fs2Lib = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-reactive-streams",
  "co.fs2" %% "fs2-io"
).map(_ % "3.2.7")

val monocleLib = Seq(
  "com.github.julien-truffaut" %% "monocle-core",
  "com.github.julien-truffaut" %% "monocle-generic",
  "com.github.julien-truffaut" %% "monocle-macro",
  "com.github.julien-truffaut" %% "monocle-state",
  "com.github.julien-truffaut" %% "monocle-unsafe"
).map(_ % monocle)

val sparkLib = Seq(
  "org.apache.spark" %% "spark-catalyst",
  "org.apache.spark" %% "spark-core",
  "org.apache.spark" %% "spark-sql",
  "org.apache.spark" %% "spark-streaming",
  "org.apache.spark" %% "spark-streaming-kafka-0-10",
  "org.apache.spark" %% "spark-sql-kafka-0-10",
  "org.apache.spark" %% "spark-avro",
  "org.apache.spark" %% "spark-graphx"
).map(_ % "3.2.1") ++ Seq(
  "org.typelevel" %% "frameless-dataset",
  "org.typelevel" %% "frameless-core"
).map(_ % "0.12.0") ++ Seq(
  "org.apache.avro" % "avro-compiler",
  "org.apache.avro" % "avro-mapred"
).map(_ % avro)

val testLib = Seq(
  "org.typelevel" %% "cats-effect-testkit"                    % catsEffect,
  "org.typelevel" %% "cats-testkit-scalatest"                 % "2.1.5",
  "org.typelevel" %% "discipline-scalatest"                   % "2.1.5",
  "org.typelevel" %% "cats-laws"                              % catsCore,
  "com.github.alexarchambault" %% "scalacheck-shapeless_1.15" % "1.3.0",
  "org.scalatest" %% "scalatest"                              % "3.2.12",
  "com.github.julien-truffaut" %% "monocle-law"               % monocle,
  "com.47deg" %% "scalacheck-toolbox-datetime"                % "0.6.0",
  "org.tpolecat" %% "doobie-postgres"                         % "1.0.0-RC2",
  "com.typesafe.akka" %% "akka-stream-testkit"                % akka26,
  "org.typelevel" %% "algebra-laws"                           % algebra,
  "com.typesafe.akka" %% "akka-stream-kafka-testkit"          % "3.0.0",
  "com.github.pathikrit" %% "better-files"                    % "3.9.1",
  "org.slf4j"                                                 % "slf4j-log4j12" % "1.7.36"
).map(_ % Test)

val kafkaLib = Seq(
  "io.confluent"                              % "kafka-schema-registry-client" % confluent,
  "io.confluent"                              % "kafka-schema-serializer"      % confluent,
  "org.apache.kafka"                          % "kafka-streams"                % kafkaVersion,
  "org.apache.kafka" %% "kafka-streams-scala" % kafkaVersion,
  "com.typesafe.akka" %% "akka-stream-kafka"  % "3.0.0",
  "com.github.fd4s" %% "fs2-kafka"            % "3.0.0-M7"
).map(_.exclude("org.apache.kafka", "kafka-clients")) ++ Seq(
  "org.apache.kafka" % "kafka-clients" % kafkaVersion
)

val enumLib = Seq(
  "com.beachape" %% "enumeratum-cats",
  "com.beachape" %% "enumeratum",
  "com.beachape" %% "enumeratum-circe"
).map(_ % "1.7.0")

val drosteLib = Seq(
  "io.higherkindness" %% "droste-core",
  "io.higherkindness" %% "droste-macros",
  "io.higherkindness" %% "droste-meta"
).map(_ % "0.8.0")

val catsLib = Seq(
  "org.typelevel" %% "cats-kernel",
  "org.typelevel" %% "cats-core",
  "org.typelevel" %% "cats-free",
  "org.typelevel" %% "alleycats-core"
).map(_ % catsCore) ++
  Seq(
    "org.typelevel" %% "cats-mtl"              % "1.2.1",
    "org.typelevel" %% "kittens"               % "2.3.2",
    "org.typelevel" %% "cats-tagless-macros"   % "0.14.0",
    "org.typelevel" %% "algebra"               % algebra,
    "org.typelevel" %% "cats-collections-core" % "0.9.3"
  )

val refinedLib = Seq(
  "eu.timepit" %% "refined",
  "eu.timepit" %% "refined-cats"
).map(_ % "0.9.29")

val akkaLib = Seq(
  "com.typesafe.akka" %% "akka-actor-typed",
  "com.typesafe.akka" %% "akka-actor",
  "com.typesafe.akka" %% "akka-protobuf",
  "com.typesafe.akka" %% "akka-stream-typed",
  "com.typesafe.akka" %% "akka-stream"
).map(_ % akka26)

val effectLib = Seq(
  "org.typelevel" %% "cats-effect" % catsEffect,
  "dev.zio" %% "zio"               % "1.0.14"  % Provided,
  "dev.zio" %% "zio-interop-cats"  % "3.2.9.1" % Provided,
  "io.monix" %% "monix-eval"       % "3.4.1"   % Provided,
  "io.monix" %% "monix"            % "3.4.1"   % Provided
)

val ftpLib = Seq(
  "commons-net"                                     % "commons-net" % "3.8.0",
  "com.hierynomus"                                  % "sshj"        % "0.33.0",
  "com.lightbend.akka" %% "akka-stream-alpakka-ftp" % "3.0.4"
)

val logLib = Seq(
  "org.typelevel" %% "log4cats-slf4j" % "2.3.1",
  "org.slf4j"                         % "slf4j-api" % "1.7.36"
)

val http4sLib = Seq(
  "org.http4s" %% "http4s-blaze-server",
  "org.http4s" %% "http4s-blaze-client",
  "org.http4s" %% "http4s-circe",
  "org.http4s" %% "http4s-dsl"
).map(_ % "0.23.11")

val jwtLib = Seq(
  "org.bouncycastle" % "bcpkix-jdk15on" % "1.70",
  "io.jsonwebtoken"  % "jjwt-api"       % "0.11.5",
  "io.jsonwebtoken"  % "jjwt-impl"      % "0.11.5",
  "io.jsonwebtoken"  % "jjwt-jackson"   % "0.11.5"
)

val metricLib = Seq(
  "io.dropwizard.metrics" % "metrics-core" % "4.2.9",
  "io.dropwizard.metrics" % "metrics-json" % "4.2.9",
  "io.dropwizard.metrics" % "metrics-jmx"  % "4.2.9",
  "io.dropwizard.metrics" % "metrics-jvm"  % "4.2.9"
)

val cronLib = Seq(
  "eu.timepit" %% "fs2-cron-cron4s"                 % "0.7.2",
  "com.github.alonsodomin.cron4s" %% "cron4s-core"  % "0.6.1",
  "com.github.alonsodomin.cron4s" %% "cron4s-circe" % "0.6.1"
)

val baseLib = Seq(
  "org.apache.commons"                             % "commons-lang3" % "3.12.0",
  "org.typelevel" %% "squants"                     % "1.8.3",
  "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2",
  "org.typelevel" %% "case-insensitive"            % "1.2.0",
  "io.scalaland" %% "chimney"                      % "0.6.1",
  "io.scalaland" %% "enumz"                        % "1.0.0",
  "com.twitter" %% "algebird-core"                 % "0.13.9",
  "com.chuusai" %% "shapeless"                     % "2.3.9"
) ++ enumLib ++ drosteLib ++ catsLib ++ refinedLib ++ circeLib ++ monocleLib

lazy val common = (project in file("common"))
  .settings(commonSettings: _*)
  .settings(name := "nj-common")
  .settings(
    libraryDependencies ++= Seq(
      "io.dropwizard.metrics"             % "metrics-core" % "4.2.9" % Provided,
      "org.typelevel" %% "log4cats-slf4j" % "2.3.1"        % Provided) ++
      baseLib ++ testLib
  )

lazy val http = (project in file("http"))
  .dependsOn(common)
  .settings(commonSettings: _*)
  .settings(name := "nj-http")
  .settings(libraryDependencies ++= jwtLib ++ http4sLib ++ logLib ++ effectLib ++ fs2Lib ++ testLib)

lazy val aws = (project in file("aws"))
  .dependsOn(common)
  .settings(commonSettings: _*)
  .settings(name := "nj-aws")
  .settings(libraryDependencies ++= awsLib ++ logLib ++ effectLib ++ fs2Lib ++ testLib)

lazy val datetime = (project in file("datetime"))
  .dependsOn(common)
  .settings(commonSettings: _*)
  .settings(name := "nj-datetime")
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "fastparse"   % "2.3.3",
      "org.typelevel" %% "cats-time" % "0.5.0") ++ cronLib ++ testLib
  )

lazy val guard = (project in file("guard"))
  .dependsOn(aws)
  .dependsOn(datetime)
  .settings(commonSettings: _*)
  .settings(name := "nj-guard")
  .settings(
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "cats-retry-mtl" % "3.1.0",
      "com.lihaoyi" %% "scalatags"           % "0.11.1",
      "org.tpolecat" %% "skunk-core"         % "0.3.1",
      "org.tpolecat" %% "skunk-circe"        % "0.3.1"
    ) ++ cronLib ++ metricLib ++ logLib ++ effectLib ++ fs2Lib ++ testLib
  )

lazy val messages = (project in file("messages"))
  .dependsOn(datetime)
  .settings(commonSettings: _*)
  .settings(name := "nj-messages")
  .settings(libraryDependencies ++= baseLib ++ serdeLib ++ kafkaLib.map(_ % Provided) ++ testLib)

lazy val pipes = (project in file("pipes"))
  .dependsOn(common)
  .settings(commonSettings: _*)
  .settings(name := "nj-pipes")
  .settings(
    libraryDependencies ++= Seq("org.tukaani" % "xz" % "1.9") ++
      kantanLib ++ ftpLib ++ akkaLib ++ hadoopLib ++
      serdeLib ++ logLib ++ effectLib ++ fs2Lib ++ testLib
  )

lazy val database = (project in file("database"))
  .dependsOn(common)
  .settings(commonSettings: _*)
  .settings(name := "nj-database")
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core"   % "1.0.0-RC2",
      "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC2",
      "org.tpolecat" %% "doobie-free"   % "1.0.0-RC2",
      "org.tpolecat" %% "skunk-core"    % "0.3.1",
      "com.zaxxer"                      % "HikariCP" % "5.0.1"
    ) ++ effectLib ++ fs2Lib ++ testLib
  )

lazy val kafka = (project in file("kafka"))
  .dependsOn(messages)
  .dependsOn(datetime)
  .dependsOn(common)
  .settings(commonSettings: _*)
  .settings(name := "nj-kafka")
  .settings(
    libraryDependencies ++= serdeLib ++ kafkaLib ++ akkaLib ++ logLib ++ effectLib ++ fs2Lib ++ testLib
  )

lazy val spark = (project in file("spark"))
  .dependsOn(kafka)
  .dependsOn(pipes)
  .dependsOn(database)
  .settings(commonSettings: _*)
  .settings(name := "nj-spark")
  .settings(
    libraryDependencies ++= Seq(
      "io.netty"                               % "netty-all" % "4.1.77.Final",
      "com.julianpeeters" %% "avrohugger-core" % "1.0.0"     % Test) ++
      sparkLib ++ serdeLib ++ kantanLib ++ hadoopLib ++ kafkaLib ++
      akkaLib ++ ftpLib ++ logLib ++ effectLib ++ fs2Lib ++ testLib
  )

lazy val example = (project in file("example"))
  .dependsOn(common)
  .dependsOn(datetime)
  .dependsOn(http)
  .dependsOn(aws)
  .dependsOn(guard)
  .dependsOn(messages)
  .dependsOn(pipes)
  .dependsOn(kafka)
  .dependsOn(database)
  .dependsOn(spark)
  .settings(commonSettings: _*)
  .settings(name := "nj-example")
  .settings(libraryDependencies ++= testLib)
  .settings(Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"))

lazy val nanjin =
  (project in file(".")).aggregate(common, datetime, http, aws, guard, messages, pipes, kafka, database, spark)
