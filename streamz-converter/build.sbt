name := "streamz-converter"

libraryDependencies ++= Seq(
  "co.fs2"            %% "fs2-core"            % Version.Fs2,
  "org.typelevel"     %% "cats-effect-std"     % Version.CatsEffect,
  "org.typelevel"     %% "cats-effect"         % Version.CatsEffect % "test",
  "com.typesafe.akka" %% "akka-stream"         % Version.Akka,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka % "test",
  "com.typesafe.akka" %% "akka-testkit"        % Version.Akka % "test",
  "org.scala-lang.modules" %% "scala-collection-compat" % Version.ScalaCollectionCompat % "test",
  compilerPlugin("com.github.ghik" % "silencer-plugin" % Version.Silencer cross CrossVersion.full),
  "com.github.ghik" % "silencer-lib" % Version.Silencer % Provided cross CrossVersion.full
)
