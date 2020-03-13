resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.0")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.3")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.7")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.4")
