scalaVersion in ThisBuild := "2.11.0"

organization := "com.github.krasserm"

name := "streamz"

version := "0.1-SNAPSHOT"

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

scalacOptions ++= Seq("-feature", "-language:higherKinds", "-language:implicitConversions")

lazy val root = (project.in(file("."))).aggregate(common, akkaCamel, akkaPersistence)

lazy val common = project.in(file("common"))

lazy val akkaCamel = project.in(file("akka-camel")).dependsOn(common)

lazy val akkaPersistence = project.in(file("akka-persistence")).dependsOn(common)
