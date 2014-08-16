name := "streamz-akka-persistence"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-experimental" % Version.Akka,
  "commons-io" % "commons-io" % Version.CommonsIO % "test"
)
