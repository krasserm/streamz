package streamz.akka.persistence

import java.io.File

import akka.actor._
import akka.persistence._
import akka.testkit.TestKit

import scalaz._
import Scalaz._
import scalaz.stream.Process

import org.apache.commons.io.FileUtils
import org.scalatest._

class PersistenceSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  override protected def afterAll(): Unit = {
    system.shutdown()
    List(
      "akka.persistence.journal.leveldb.dir",
      "akka.persistence.snapshot-store.local.dir")
      .map(s ⇒ new File(system.settings.config.getString(s)))
      .foreach(FileUtils.deleteDirectory)
  }

  class TestProcessor(override val processorId: String, probe: ActorRef = testActor) extends Processor {
    var state: String = ""

    def receive = {
      case Persistent(p: String, snr) =>
        state = state + p
      case "snap" =>
        saveSnapshot(state)
      case SaveSnapshotSuccess(md) =>
        probe ! md
    }
  }

  "A replayer" must {
    "produce a discrete stream of journaled messages" in {
      val p = system.actorOf(Props(new TestProcessor("p1")))
      1 to 3 foreach { i => p ! Persistent(i) }
      replay("p1").map(p => (p.payload, p.sequenceNr)).take(3).runLog.run should be(Seq((1, 1L), (2, 2L), (3, 3L)))
    }
    "produce a discrete stream of journaled messages from user-defined sequence number" in {
      val p = system.actorOf(Props(new TestProcessor("p2")))
      1 to 3 foreach { i => p ! Persistent(i) }
      replay("p2", 2L).map(p => (p.payload, p.sequenceNr)).take(2).runLog.run should be(Seq((2, 2L), (3, 3L)))
    }
  }

  "A journal" must {
    "journal a stream of messages" in {
      Process("a", "b", "c").journal("p3").run.run
      replay("p3").map(p => (p.payload, p.sequenceNr)).take(3).runLog.run should be(Seq(("a", 1L), ("b", 2L), ("c", 3L)))
    }
  }

  "A snapshot loader" must {
    "produce the most recent snapshot" in {
      val p = system.actorOf(Props(new TestProcessor("p4")))
      p ! Persistent("a")
      p ! Persistent("b")
      p ! "snap"

      val metadata = expectMsgPF() { case md: SnapshotMetadata => md }
      snapshot[String]("p4").runLog.run should be(Seq(Snapshot(metadata, "ab")))
    }
    "produce a zero snapshot if there's no snapshot stored" in {
      snapshot[String]("p5").runLog.run should be(Seq(Snapshot(SnapshotMetadata("p5", 0L, 0L), "")))
    }
  }

  "A composition of snapshot and replay" must {
    "produce a discrete stream of updated states" in {
      val p = system.actorOf(Props(new TestProcessor("p6")))
      p ! Persistent("a")
      p ! Persistent("b")
      p ! "snap"
      p ! Persistent("c")
      p ! Persistent("d")
      expectMsgPF() { case md: SnapshotMetadata => md }

      val c = for {
        s @ Snapshot(meta, data) <- snapshot[String]("p6")
        state <- replay(meta.processorId, s.nextSequenceNr).map(_.payload).scan(data)((acc,p) => acc + p)
      } yield state

      c.take(3).runLog.run should be(Seq("ab", "abc", "abcd"))
    }
  }
}
