Streamz
=======

[![Gitter](https://badges.gitter.im/krasserm/streamz.svg)](https://gitter.im/krasserm/streamz?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Build Status](https://travis-ci.org/krasserm/streamz.svg?branch=master)](https://travis-ci.org/krasserm/streamz)

Streamz provides combinator libraries for integrating [Functional Streams for Scala](https://github.com/functional-streams-for-scala/fs2) (FS2), [Akka Streams](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) and [Apache Camel endpoints](http://camel.apache.org/components.html). They integrate

- **Apache Camel with Akka Streams:** Camel endpoints can be integrated into Akka Stream applications with the [Camel DSL for Akka Streams](streamz-camel-akka/README.md).
- **Apache Camel with FS2:** Camel endpoints can be integrated into FS2 applications with the [Camel DSL for FS2](streamz-camel-fs2/README.md).
- **Akka Streams with FS2:** Akka Stream `Source`s, `Flow`s and `Sink`s can be converted to FS2 `Stream`s, `Pipe`s and `Sink`s, respectively, and vice versa with [Stream converters](streamz-converter/README.md).

![Streamz intro](images/streamz-intro.png)

Dependencies
------------

Streamz artifacts are available for Scala 2.11, 2.12, and 2.13 at:

    resolvers += "streamz at bintray" at "http://dl.bintray.com/streamz/maven"
    val streamzVersion = "0.11-RC1"

    libraryDependencies ++= Seq(
      "com.github.krasserm" %% "streamz-camel-akka" % streamzVersion,
      "com.github.krasserm" %% "streamz-camel-fs2" % streamzVersion,
      "com.github.krasserm" %% "streamz-converter" % streamzVersion,
    )

### Latest milestone release for FS2 1.0.x

    val streamzVersion = "0.10-M2"

### Latest stable release for FS2 0.10.x

    val streamzVersion = "0.9.1" 

### Latest stable release for FS2 0.9.x

    val streamzVersion = "0.8.1"

Documentation
-------------

### Streamz 0.10-M2

- [Camel DSL for Akka Streams](streamz-camel-akka/README.md)
- [Camel DSL for FS2](streamz-camel-fs2/README.md)
- [Stream converters](streamz-converter/README.md)
- [Example application](streamz-examples/README.md)

### Streamz 0.9.1

- [Camel DSL for Akka Streams](https://github.com/krasserm/streamz/blob/v-0.9.1/streamz-camel-akka/README.md)
- [Camel DSL for FS2](https://github.com/krasserm/streamz/blob/v-0.9.1/streamz-camel-fs2/README.md)
- [Stream converters](https://github.com/krasserm/streamz/blob/v-0.9.1/streamz-converter/README.md)
- [Example application](https://github.com/krasserm/streamz/blob/v-0.9.1/streamz-examples/README.md)

### Streamz 0.8.1

- [Camel DSL for Akka Streams](https://github.com/krasserm/streamz/blob/v-0.8.1/streamz-camel-akka/README.md)
- [Camel DSL for FS2](https://github.com/krasserm/streamz/blob/v-0.8.1/streamz-camel-fs2/README.md)
- [Stream converters](https://github.com/krasserm/streamz/blob/v-0.8.1/streamz-converter/README.md)
- [Example application](https://github.com/krasserm/streamz/blob/v-0.8.1/streamz-examples/README.md)

API docs
--------

## Latest version (0.11-RC1)
- [API docs for Scala 2.13](http://krasserm.github.io/streamz/scala-2.13/unidoc/index.html)
- [API docs for Scala 2.12](http://krasserm.github.io/streamz/scala-2.12/unidoc/index.html)
- [API docs for Scala 2.11](http://krasserm.github.io/streamz/scala-2.11/unidoc/index.html)

## Older versions

### Streamz 0.10-M2

Not published. Run `sbt unidoc` on tag `0.10-M2` for generating 0.10 API docs.

### Streamz 0.9.1

Not published. Run `sbt unidoc` on branch `r-0.9` for generating 0.9 API docs. 

### Streamz 0.8.1

Not published. Run `sbt unidoc` on branch `r-0.8` for generating 0.8 API docs. 

External examples
-----------------

- [Serve static files from an FS2 stream in an Akka HTTP server](https://gist.github.com/bmc/2db513245a4d7213ba7aba4f67723d12).
