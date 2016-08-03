package akka.persistence

import akka.actor._

import streamz.akka.persistence.Event

class EventReader(pid: String, from: Long) extends Actor {
  import EventReader._
  import BufferedView._

  val view = context.actorOf(Props(new BufferedView(pid, BufferedViewSettings(fromSequenceNr = from), self)))
  var callback: Option[Either[Throwable, Event[Any]] => Unit] = None

  def receive = {
    case Response(ps) => for {
      p <- ps.headOption
      cb <- callback
    } {
      cb(Right(p))
      callback = None
    }
    case Read(cb) =>
      callback = Some(cb)
      view ! Request(1)
  }
}

object EventReader {
  case class Read(callback: Either[Throwable, Event[Any]] => Unit)
}
