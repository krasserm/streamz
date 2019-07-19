name := "streamz-camel-context"

libraryDependencies ++= Seq(
  "com.typesafe"     % "config"     % Version.Config,
  "org.apache.camel" % "camel-core" % Version.Camel,
  "org.scala-lang.modules" %% "scala-collection-compat" % Version.ScalaCollectionCompat)
