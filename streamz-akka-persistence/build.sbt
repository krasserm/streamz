name := "streamz-akka-persistence"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence" % Version.Akka,
  "org.iq80.leveldb" % "leveldb" % "0.7",
  "commons-io" % "commons-io" % Version.CommonsIO % "test"
)
