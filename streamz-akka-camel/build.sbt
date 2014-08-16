name := "streamz-akka-camel"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-camel" % Version.Akka,
  "org.apache.camel" % "camel-ftp" % "2.10.3" % "test"
)