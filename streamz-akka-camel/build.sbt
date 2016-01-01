name := "streamz-akka-camel"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-camel" % Version.Akka,
  "org.apache.camel" % "camel-ftp" % "2.13.4" % "test"
)