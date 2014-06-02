package akka.persistence

import scala.concurrent.duration.FiniteDuration

import akka.actor._

case class BufferedViewSettings(fromSequenceNr: Long = 1L, maxBufferSize: Int = 100, idle: Option[FiniteDuration] = None) {
  require(fromSequenceNr > 0L, "fromSequenceNr must be > 0")
}

object BufferedView {
  case class Request(num: Int)
  case class Response(messages: Vector[Persistent])

  case object Fill
  case object Filled
}

/**
 * From [[https://github.com/akka/akka/blob/release-2.3-dev/akka-stream/src/main/scala/akka/persistence/stream/PersistentPublisher.scala]].
 */
class BufferedView(override val processorId: String, publisherSettings: BufferedViewSettings, publisher: ActorRef) extends View {
  import BufferedView._
  import context.dispatcher

  private var replayed = 0
  private var requested = 0
  private var buffer: Vector[Persistent] = Vector.empty

  private val filling: Receive = {
    case p: Persistent ⇒
      buffer :+= p
      replayed += 1
      if (requested > 0) respond(requested)
    case Filled ⇒
      if (buffer.nonEmpty && requested > 0) respond(requested)
      if (buffer.nonEmpty) pause()
      else if (replayed > 0) fill()
      else schedule()
    case Request(num) ⇒
      requested += num
      if (buffer.nonEmpty) respond(requested)
  }

  private val pausing: Receive = {
    case Request(num) ⇒
      requested += num
      respond(requested)
      if (buffer.isEmpty) fill()
  }

  private val scheduled: Receive = {
    case Fill ⇒
      fill()
    case Request(num) ⇒
      requested += num
  }

  def receive = filling

  override def onReplaySuccess(receive: Receive, await: Boolean): Unit = {
    super.onReplaySuccess(receive, await)
    self ! Filled
  }

  override def onReplayFailure(receive: Receive, await: Boolean, cause: Throwable): Unit = {
    super.onReplayFailure(receive, await, cause)
    self ! Filled
  }

  override def lastSequenceNr: Long =
    math.max(publisherSettings.fromSequenceNr - 1L, super.lastSequenceNr)

  override def autoUpdateInterval: FiniteDuration =
    publisherSettings.idle.getOrElse(super.autoUpdateInterval)

  override def autoUpdateReplayMax: Long =
    publisherSettings.maxBufferSize

  override def autoUpdate: Boolean =
    false

  private def fill(): Unit = {
    replayed = 0
    context.become(filling)
    self ! Update(await = false, autoUpdateReplayMax)
  }

  private def pause(): Unit = {
    context.become(pausing)
  }

  private def schedule(): Unit = {
    context.become(scheduled)
    context.system.scheduler.scheduleOnce(autoUpdateInterval, self, Fill)
  }

  private def respond(num: Int): Unit = {
    val (res, buf) = buffer.splitAt(num)
    publisher ! Response(res)
    buffer = buf
    requested -= res.size
  }
}
