import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

// ---------------------------------------------------------------------------
//  Main settings
// ---------------------------------------------------------------------------

name := "streamz"

organization in ThisBuild := "com.github.krasserm"

version in ThisBuild := "0.9-SNAPSHOT"

crossScalaVersions in ThisBuild := Seq("2.11.12", "2.12.4")

scalaVersion in ThisBuild := "2.12.4"

scalacOptions in ThisBuild ++= Seq("-feature", "-language:higherKinds", "-language:implicitConversions", "-deprecation")

libraryDependencies in ThisBuild += "org.scalatest" %% "scalatest" % Version.Scalatest % "test"

// ---------------------------------------------------------------------------
//  Code formatter settings
// ---------------------------------------------------------------------------

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DanglingCloseParenthesis, Preserve)
  .setPreference(DoubleIndentConstructorArguments, false)

// ---------------------------------------------------------------------------
//  License header settings
// ---------------------------------------------------------------------------

lazy val header = HeaderLicense.ALv2("2014 - 2017", "the original author or authors.")

lazy val headerSettings = Seq(
  headerLicense := Some(header)
)

// ---------------------------------------------------------------------------
//  Projects
// ---------------------------------------------------------------------------

lazy val root = project.in(file("."))
  .aggregate(camelContext, camelAkka, camelFs2, converter, examples)
  .settings(unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(examples))
  .enablePlugins(ScalaUnidocPlugin)

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
