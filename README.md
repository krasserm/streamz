Streamz
=======

Streamz is a [scalaz-stream](https://github.com/scalaz/scalaz-stream) combinator library for [Akka Persistence](http://doc.akka.io/docs/akka/2.3.3/scala/persistence.html) and [Apache Camel](http://camel.apache.org/). It supports the composition of ``scalaz.stream.Process`` instances from [Camel]() endpoints, Persistence journals and snapshot stores.

Combinators for Apache Camel
----------------------------

The following examples need these imports

    import akka.actor.ActorSystem
    import scalaz.concurrent.Task
    import scalaz.stream.Process
    import streamz.akka.camel._

and an implicit ``ActorSystem`` in scope (the combinator implementation depends on [Akka Camel](http://doc.akka.io/docs/akka/2.3.3/scala/camel.html)).  

A discrete stream starting from a Camel endpoint can be created with ``receive`` where the ``receive`` type parameter is used to convert message bodies to given type using a Camel type converter, if needed.

    val p1: Process[Task,String] = receive[String]("seda:q1")

Streams can also be targeted at Camel endpoints. The ``send`` and ``sendW`` combinators initiate in-only message exchanges with a Camel endpoint.  

    val p2: Process[Task,Unit] = p1.send("seda:q2")
    val p3: Process[Task,String] = p1.sendW("seda:q3")
    
The ``request`` combinator initiates in-out message exchanges with a Camel endpoint where the ``request`` type parameter is used to convert response message bodies to given type using a Camel type converter, if needed.

    val p4: Process[Task,Int] = p1.request[Int]("bean:service?method=length")
   
These combinators can be used in combination with all available [Camel components](http://camel.apache.org/components.html) and all ``scalaz.stream.Process`` combinators. 

Combinators for Akka Persistence
--------------------------------

Status
------

- Preview
- Experimental
