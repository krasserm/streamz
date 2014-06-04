Streamz
=======

Streamz is a [scalaz-stream](https://github.com/scalaz/scalaz-stream) combinator library for [Akka Persistence](http://doc.akka.io/docs/akka/2.3.3/scala/persistence.html) and [Apache Camel](http://camel.apache.org/). It supports the composition of ``scalaz.stream.Process`` instances from Camel endpoints, Persistence journals and snapshot stores.

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

The following examples need these imports

    import akka.actor.ActorSystem
    import akka.persistence.Persistent
    import scalaz.concurrent.Task
    import scalaz.std.string._
    import scalaz.stream.Process

    import streamz.akka.persistence._

and an implicit ``ActorSystem`` in scope.  

A discrete stream of ``Persistent`` messages (that are written by an ``akka.persistence.Processor`` or ``akka.persistence.EventsourcedProcessor`` elsewhere) can be created with ``replay`` and a mandatory processor id and an optional start sequence number (``from``) parameter: 

    val p1: Process[Task, Persistent] = replay("processor-1")
    val p2: Process[Task, Persistent] = replay("processor-1", from = 3L)

The created ``Process[Task,Persistent]`` does not only replay already journaled messages but also emits new messages that ``processor-1`` is going to write. Assuming journaled messages  ``Persistent("a", 1L)``, ``Persistent("b", 2L)``, ``Persistent("c", 3L)``, ``Persistent("d", 4L)``, ``p1`` produces ``Persistent("a", 1L)``, ``Persistent("b", 2L)``, ``Persistent("c", 3L)``, ``Persistent("d", 4L)`` and ``p2`` produces ``Persistent("c", 3L)``, ``Persistent("d", 4L)``. 

State can be accumulated with with scalaz-stream's ``scan``

    val p3: Process[Task, String] = p1.scan("")((acc, p) => acc + p.payload)

which produces ``""``, ``"a"``, ``"ab"``, ``"abc"``, ``"abcd"``. State accumulation from a snapshot can be done with ``snapshot``:
 
    val p4: Process[Task,String] = for {
      s @ Snapshot(meta, data) <- snapshot[String]("processor-1")
      state <- replay(meta.processorId, s.nextSequenceNr).scan(data)((acc, p) => acc + p.payload)
    } yield state

Additionally assuming that a snapshot ``"ab"`` has already been written by ``processor-1`` (after messages ``Persistent("a", 1L)`` and ``Persistent("b", 2L)``), ``p4`` produces ``"ab"``, ``"abc"``, ``"abcd"``.

Finally, writing to an Akka Persistence journal from a stream can done with ``journal``:

    val p5: Process[Task,Unit] = Process("a", "b", "c").journal("processor-2")

Status
------

- Preview
- Experimental
