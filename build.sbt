lazy val scala212 = "2.12.15"

ThisBuild / scalaVersion := scala212

ThisBuild / version := "3.0.2"

lazy val supportedScalaVersions = List(scala212)

lazy val commonSettings = Seq(
  organization := "com.datto",
  crossScalaVersions := supportedScalaVersions,
  scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Xlint",
    "-Xfatal-warnings")) ++ stylePreferences

run / fork := true
run / javaOptions += "-Xmx8G -XX:+PrintGC"

val akkaV       = "2.6.4"
val scalaTestV  = "3.0.8"

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    testkit,
    coreTests
  ).settings(
    // crossScalaVersions must be set to Nil on the aggregating project
    crossScalaVersions := Nil,
    publish / skip := true
  )


lazy val core = (project in file("core")).
  settings(commonSettings: _*).
  settings(
    name := "flow").
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"      %% "akka-actor"                           % akkaV,
        "com.typesafe.akka"      %% "akka-stream"                          % akkaV
      )
    }
  )

lazy val testkit = (project in file("testkit")).
  settings(commonSettings: _*).
  settings(
    name := "flow-testkit").
  settings(
    libraryDependencies ++= {
      Seq(
        "org.scalatest"          %% "scalatest"                            % scalaTestV,
        "com.typesafe.akka"      %% "akka-testkit"                         % akkaV
      )
    }
  ).dependsOn(core)

lazy val coreTests = (project in file("core-tests")).
  settings(commonSettings: _*).
  settings(
    name := "flow-tests").
  settings(
    libraryDependencies ++= {
      Seq()
    }
  ).dependsOn(core, testkit)


lazy val stylePreferences = Seq(
  Compile / compile / wartremoverWarnings ++= Seq(
    Wart.StringPlusAny,
    Wart.AsInstanceOf,
    Wart.IsInstanceOf,
    Wart.JavaConversions,
    Wart.TraversableOps,
    Wart.MutableDataStructures,
    Wart.Null,
    Wart.Return,
    Wart.TryPartial,
    Wart.OptionPartial,
    Wart.Var,
    Wart.While))

publishMavenStyle := true

ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

pomIncludeRepository := { _ => false }

Test / publishArtifact  := false

sonatypeProfileName := "com.datto"

ThisBuild / pomExtra := (
  <url>https://github.com/backupify/datto-flow</url>
  <licenses>
    <license>
      <name>MIT</name>
      <url>https://github.com/backupify/datto-flow/blob/master/LICENSE.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:backupify/datto-flow.git</url>
    <connection>scm:git:git@github.com:backupify/datto-flow.git</connection>
  </scm>
  <developers>
    <developer>
      <id>anorwell</id>
      <name>Arron Norwell</name>
      <url>http://anorwell.com</url>
    </developer>
  </developers>)
