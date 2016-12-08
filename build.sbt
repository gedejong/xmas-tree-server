name := "xmas-stream"

version := "1.0"

scalaVersion := "2.12.1"

resolvers += Resolver.typesafeRepo("releases")

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

libraryDependencies ++= Seq(
  // Flow (akka-streams serial)
  "ch.jodersky" %% "flow-core" % "3.0.4",
  "ch.jodersky" % "flow-native" % "3.0.4" % "runtime",
  "ch.jodersky" %% "flow-stream" % "3.0.4",

  // Scodec (binary encoding / decoding)
  "org.scodec" %% "scodec-bits" % "1.1.2",
  "org.scodec" %% "scodec-core" % "1.10.3",

  // Play: JSON interface
  "com.typesafe.play" %% "play-json" % "2.6.0-SNAPSHOT",

  // Akka http
  "com.typesafe.akka" %% "akka-http-core" % "10.0.0",
  "com.typesafe.akka" %% "akka-http" % "10.0.0",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.0.0",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.0",

  // Akka
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.14",

// Logging
  "ch.qos.logback" % "logback-classic" % "1.1.3"
)
