name := "streamz-akka-persistence"

libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.2.4",
  "com.typesafe.akka" %% "akka-persistence" % Version.Akka,
  "org.iq80.leveldb" % "leveldb" % "0.7",
  "commons-io" % "commons-io" % Version.CommonsIO % "test"
)
