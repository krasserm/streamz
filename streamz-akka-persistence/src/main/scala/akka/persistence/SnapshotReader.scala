package akka.persistence

import akka.actor._
import akka.persistence.SnapshotProtocol._

import scalaz._
import Scalaz._

class SnapshotReader extends Actor {
  import SnapshotReader._

  val store = Persistence(context.system).snapshotStoreFor(null)
  var callback: Option[Throwable \/ Option[SelectedSnapshot] => Unit] = None

  def receive = {
    case LoadSnapshotResult(sso,_) =>
      callback.foreach(_.apply(sso.right))
      callback = None
    case Read(pid, cb) =>
      callback = Some(cb)
      store ! LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue)
  }
}

object SnapshotReader {
  case class Read(pid: String, callback: Throwable \/ Option[SelectedSnapshot] => Unit)
}