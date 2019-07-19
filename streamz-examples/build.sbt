name := "streamz-examples"

libraryDependencies ++= Seq(
  "org.apache.camel"         % "camel-jetty"      % Version.Camel,
  "org.apache.camel"         % "camel-netty4"     % Version.Camel,
  "org.apache.camel"         % "camel-stream"     % Version.Camel,
  "org.apache.logging.log4j" % "log4j-api"        % Version.Log4j,
  "org.apache.logging.log4j" % "log4j-core"       % Version.Log4j,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % Version.Log4j
)

// We need to silence unused-import warning on scala 2.13,
// because scala-collection-compat library generates empty importable objects.
scalacOptions -= "-Wunused:imports"
scalacOptions --= {
  if (scalaVersion.value.startsWith("2.11"))
    // Deprecation warnings can't be disabled, so we have to remove fatal-warnings to allow use of `String#linesIterator`
    Seq("-Xfatal-warnings")
  else
    Seq()
}