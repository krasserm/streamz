name := "streamz-examples"

libraryDependencies ++= Seq(
  "org.apache.camel"         % "camel-netty4"     % Version.Camel,
  "org.apache.camel"         % "camel-stream"     % Version.Camel,
  "org.apache.logging.log4j" % "log4j-api"        % Version.Log4j,
  "org.apache.logging.log4j" % "log4j-core"       % Version.Log4j,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % Version.Log4j
)
