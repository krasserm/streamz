name := "streamz-akka-stream"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Version.Akka,
  "com.typesafe.akka" %% "akka-stream" % Version.Akka,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka % "test",
  "com.typesafe.akka" %% "akka-testkit" % Version.Akka % "test"
)
