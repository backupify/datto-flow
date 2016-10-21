scalaVersion := "2.11.8"

version := "1.7.0"

import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

lazy val commonSettings = Seq(
  organization := "com.datto",
  version := "0.0.1",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Ywarn-unused-import",
    "-Ywarn-dead-code")) ++ stylePreferences

fork in run := true
javaOptions in run += "-Xmx8G -XX:+PrintGC"

val akkaV       = "2.4.11"
val scalaTestV  = "2.2.5"

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(
    name := "flow").
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"      %% "akka-actor"                           % akkaV,
        "com.typesafe.akka"      %% "akka-stream"                          % akkaV,
        "com.typesafe.akka"      %% "akka-http-core"                       % akkaV,
        "org.scalatest"          %% "scalatest"                            % scalaTestV,
        "com.typesafe.akka"      %% "akka-testkit"                         % akkaV % "test"
      )
    }
  )

lazy val stylePreferences = Seq(
  ScalariformKeys.preferences := ScalariformKeys.preferences.value
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(DanglingCloseParenthesis, Prevent)
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true),

  wartremoverWarnings in (Compile, compile) ++= Seq(
    Wart.Any2StringAdd,
    Wart.AsInstanceOf,
    Wart.IsInstanceOf,
    Wart.JavaConversions,
    Wart.ListOps,
    Wart.MutableDataStructures,
    Wart.Nothing,
    Wart.Null,
    Wart.Product,
    Wart.Return,
    Wart.Serializable,
    Wart.TryPartial,
    Wart.Var))

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

pomIncludeRepository := { _ => false }

pomExtra := (
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
