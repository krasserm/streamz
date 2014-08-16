package streamz.akka.persistence

case class Event[A](persistenceId: String, sequenceNr: Long, data: A)
