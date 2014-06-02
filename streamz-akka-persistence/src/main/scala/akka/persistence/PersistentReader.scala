package akka.persistence

import akka.actor._

import scalaz._
import Scalaz._

class PersistentReader(pid: String, from: Long) extends akka.actor.Actor {
  import PersistentReader._
  import BufferedView._

  val view = context.actorOf(Props(new BufferedView(pid, BufferedViewSettings(fromSequenceNr = from), self)))
  var callback: Option[Throwable \/ Persistent => Unit] = None

  def receive = {
    case Response(ps) => for {
      p <- ps.headOption
      cb <- callback
    } {
      cb(p.right)
      callback = None
    }
    case Read(cb) =>
      callback = Some(cb)
      view ! Request(1)
  }
}

object PersistentReader {
  case class Read(callback: Throwable \/ Persistent => Unit)
}
