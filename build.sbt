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

version in ThisBuild := "0.7"

crossScalaVersions in ThisBuild := Seq("2.11.8", "2.12.0")

scalaVersion in ThisBuild := "2.12.0"

scalacOptions in ThisBuild ++= Seq("-feature", "-language:higherKinds", "-language:implicitConversions", "-deprecation")

libraryDependencies in ThisBuild += "org.scalatest" %% "scalatest" % Version.Scalatest % "test"

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
  .aggregate(camelContext, camelAkka, camelFs2, converter, examples)
  .settings(unidocSettings: _*)
  .settings(unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(examples))

lazy val camelContext = project.in(file("streamz-camel-context"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)

lazy val camelAkka = project.in(file("streamz-camel-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)
  .dependsOn(camelContext)

lazy val camelFs2 = project.in(file("streamz-camel-fs2"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)
  .dependsOn(camelContext)

lazy val converter = project.in(file("streamz-converter"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)

lazy val examples = project.in(file("streamz-examples"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings)
  .dependsOn(camelAkka, camelFs2, converter)
