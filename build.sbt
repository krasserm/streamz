import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import de.heikoseeberger.sbtheader.license.Apache2_0

import scalariform.formatter.preferences._

import UnidocKeys._

// ---------------------------------------------------------------------------
//  Main settings
// ---------------------------------------------------------------------------

name := "streamz"

organization in ThisBuild := "com.github.krasserm"

version in ThisBuild := "0.6-SNAPSHOT"

crossScalaVersions in ThisBuild := Seq("2.11.8", "2.12.0")

scalaVersion in ThisBuild := "2.12.0"

scalacOptions in ThisBuild ++= Seq("-feature", "-language:higherKinds", "-language:implicitConversions", "-deprecation")

libraryDependencies in ThisBuild ++= Seq(
  "co.fs2"            %% "fs2-core"            % Version.Fs2,
  "com.typesafe.akka" %% "akka-actor"          % Version.Akka,
  "com.typesafe.akka" %% "akka-stream"         % Version.Akka,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka      % "test",
  "com.typesafe.akka" %% "akka-testkit"        % Version.Akka      % "test",
  "org.scalatest"     %% "scalatest"           % Version.Scalatest % "test"
)

// ---------------------------------------------------------------------------
//  Code formatter settings
// ---------------------------------------------------------------------------

SbtScalariform.scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DanglingCloseParenthesis, Preserve)
  .setPreference(DoubleIndentClassDeclaration, false)

// ---------------------------------------------------------------------------
//  License header settings
// ---------------------------------------------------------------------------

lazy val header = Apache2_0("2014 - 2017", "the original author or authors.")

lazy val headerSettings = Seq(headers := Map(
  "scala" -> header,
  "java" -> header
))

// ---------------------------------------------------------------------------
//  Projects
// ---------------------------------------------------------------------------

lazy val root = project.in(file("."))
  .aggregate(akka, camel, examples)
  .settings(unidocSettings: _*)
  .settings(unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(examples))

lazy val akka = project.in(file("streamz-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)

lazy val camel = project.in(file("streamz-camel"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)

lazy val examples = project.in(file("streamz-examples"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)
  .dependsOn(akka, camel)
