name := "streamz-converter"

libraryDependencies ++= Seq(
  "co.fs2"            %% "fs2-core"            % Version.Fs2,
  "org.typelevel"     %% "cats-effect"         % Version.CatsEffect,
  "com.typesafe.akka" %% "akka-stream"         % Version.Akka,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka % "test",
  "com.typesafe.akka" %% "akka-testkit"        % Version.Akka % "test")
