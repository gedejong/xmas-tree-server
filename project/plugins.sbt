logLevel := Level.Warn

resolvers += Resolver.typesafeRepo("releases")

resolvers += Resolver.url("Typesafe Ivy Snapshots Repository", url("https://oss.sonatype.org/content/repositories/snapshots/"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.4")
