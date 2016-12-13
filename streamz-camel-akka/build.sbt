name := "streamz-camel-akka"

libraryDependencies ++= Seq(
  "org.apache.camel"   % "camel-core"          % Version.Camel,
  "com.typesafe.akka" %% "akka-stream"         % Version.Akka,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka           % "test",
  "com.typesafe.akka" %% "akka-testkit"        % Version.Akka           % "test",
  "com.novocode"       % "junit-interface"     % Version.JUnitInterface % "test")
