name := "streamz-akka-persistence"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-experimental" % Version.akka,
  "commons-io"         % "commons-io"                    % Version.commonsIO % "test"
)
