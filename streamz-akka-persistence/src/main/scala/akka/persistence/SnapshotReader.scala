package akka.persistence

import akka.actor._
import akka.persistence.SnapshotProtocol._

class SnapshotReader extends Actor {
  import SnapshotReader._

  val store = Persistence(context.system).snapshotStoreFor(null)
  var callback: Option[Either[Throwable, Option[SelectedSnapshot]] => Unit] = None

  def receive = {
    case LoadSnapshotResult(sso,_) =>
      callback.foreach(_.apply(Right(sso)))
      callback = None
    case Read(pid, cb) =>
      callback = Some(cb)
      store ! LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue)
  }
}

object SnapshotReader {
  case class Read(pid: String, callback: Either[Throwable, Option[SelectedSnapshot]] => Unit)
}