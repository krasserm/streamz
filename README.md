Streamz
=======

[![Gitter](https://badges.gitter.im/krasserm/streamz.svg)](https://gitter.im/krasserm/streamz?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Build Status](https://travis-ci.org/krasserm/streamz.svg?branch=master)](https://travis-ci.org/krasserm/streamz)

Streamz is a combinator library for integrating [Functional Streams for Scala](https://github.com/functional-streams-for-scala/fs2) (FS2), [Akka Streams](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) and [Apache Camel endpoints](http://camel.apache.org/components.html). It integrates

- **Apache Camel with Akka Streams:** Camel endpoints can be integrated into Akka Stream applications with the [Camel DSL for Akka Streams](streamz-camel-akka/README.md).
- **Apache Camel with FS2:** Camel endpoints can be integrated into FS2 applications with the [Camel DSL for FS2](streamz-camel-fs2/README.md).
- **Akka Streams with FS2:** Akka Stream `Source`s, `Flow`s and `Sink`s can be converted to FS2 `Stream`s, `Pipe`s and `Sink`s, respectively, and vice versa with [Stream converters](streamz-converter/README.md).

![Streamz intro](images/streamz-intro.png)

API docs
--------

- [API docs for Scala 2.12](http://krasserm.github.io/streamz/scala-2.12/unidoc/index.html)
- [API docs for Scala 2.11](http://krasserm.github.io/streamz/scala-2.11/unidoc/index.html)

Dependencies
------------

Streamz artifacts are available for Scala 2.11 and 2.12:

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    // transitively depends on Apache Camel 2.18.0
    libraryDependencies += "com.github.krasserm" %% "streamz-camel-akka" % "0.7"

    // transitively depends on Apache Camel 2.18.0
    libraryDependencies += "com.github.krasserm" %% "streamz-camel-fs2" % "0.7"

    // transitively depends on akka-stream 2.4.14
    libraryDependencies += "com.github.krasserm" %% "streamz-converter" % "0.7"

Documentation
-------------

- [Camel DSL for Akka Streams](streamz-camel-akka/README.md)
- [Camel DSL for FS2](streamz-camel-fs2/README.md)
- [Stream converters](streamz-converter/README.md)
- [Example application](streamz-examples/README.md)

External examples
-----------------

- [Serve static files from an FS2 stream in an Akka HTTP server](https://gist.github.com/bmc/2db513245a4d7213ba7aba4f67723d12).
