import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

// ---------------------------------------------------------------------------
//  Main settings
// ---------------------------------------------------------------------------

name := "streamz"
ThisBuild / organization := "com.github.krasserm"
ThisBuild / version := "0.13-RC2"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses += "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")

ThisBuild / crossScalaVersions := Seq("2.12.12", "2.13.3")
ThisBuild / scalaVersion := "2.13.3"

ThisBuild / libraryDependencies  ++= Seq(
  "org.scalatest" %% "scalatest-wordspec" % Version.Scalatest % "test",
  "org.scalatest" %% "scalatest-shouldmatchers" % Version.Scalatest % "test",
)

// No need for `sbt doc` to fail on warnings
val docSettings = Compile / doc / scalacOptions -= "-Xfatal-warnings"

ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)

ThisBuild / bintrayOrganization := Some("streamz")
ThisBuild / bintrayReleaseOnPublish := false

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

lazy val header = HeaderLicense.ALv2("2014 - 2020", "the original author or authors.")

lazy val headerSettings = Seq(
  headerLicense := Some(header)
)

// ---------------------------------------------------------------------------
//  Projects
// ---------------------------------------------------------------------------

lazy val root = project.in(file("."))
  .aggregate(camelContext, camelAkka, camelFs2, converter, examples)
  .settings(
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(examples),
    docSettings
  )
  .enablePlugins(ScalaUnidocPlugin)

lazy val camelContext = project.in(file("streamz-camel-context"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings, docSettings)

lazy val camelAkka = project.in(file("streamz-camel-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings, docSettings)
  .dependsOn(camelContext)

lazy val camelFs2 = project.in(file("streamz-camel-fs2"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings, docSettings)
  .dependsOn(camelContext)

lazy val converter = project.in(file("streamz-converter"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings, docSettings)

lazy val examples = project.in(file("streamz-examples"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(headerSettings, docSettings)
  .dependsOn(camelAkka, camelFs2, converter)
