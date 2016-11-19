name := "streamz"

organization in ThisBuild := "com.github.krasserm"

version in ThisBuild := "0.6-SNAPSHOT"

crossScalaVersions := Seq("2.11.8", "2.12.0")

scalaVersion in ThisBuild := "2.12.0"

scalacOptions in ThisBuild ++= Seq("-feature", "-language:higherKinds", "-language:implicitConversions", "-deprecation")

libraryDependencies in ThisBuild ++= Seq(
  "co.fs2"            %% "fs2-core"      % Version.Fs2,
  "com.typesafe.akka" %% "akka-testkit"  % Version.Akka          % "test",
  "org.scalatest"     %% "scalatest"     % Version.Scalatest     % "test"
)

lazy val root = project.in(file(".")).aggregate(akkaCamel, akkaStream)

lazy val akkaCamel = project.in(file("streamz-akka-camel"))

lazy val akkaStream = project.in(file("streamz-akka-stream"))
