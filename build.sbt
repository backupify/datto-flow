scalaVersion := "2.11.8"

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

val akkaV       = "2.4.6"
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
        "org.scalatest"          %% "scalatest"                            % scalaTestV % "test",
        "com.typesafe.akka"      %% "akka-testkit"                         % akkaV % "test"
      )
    }
  )

resolvers += "Spray" at "repo.spray.io"


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
