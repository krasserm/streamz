name := "streamz-converter"

libraryDependencies in ThisBuild ++= Seq(
  "co.fs2"            %% "fs2-core"            % Version.Fs2,
  "com.typesafe.akka" %% "akka-stream"         % Version.Akka,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka % "test",
  "com.typesafe.akka" %% "akka-testkit"        % Version.Akka % "test")
