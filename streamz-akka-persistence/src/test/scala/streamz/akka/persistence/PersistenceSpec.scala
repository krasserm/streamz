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
    system.terminate()
    List(
      "akka.persistence.journal.leveldb.dir",
      "akka.persistence.snapshot-store.local.dir")
      .map(s ⇒ new File(system.settings.config.getString(s)))
      .foreach(FileUtils.deleteDirectory)
  }

  class TestPersistentActor(val persistenceId: String, probe: ActorRef = testActor) extends PersistentActor {
    var state: String = ""

    override def receiveRecover = {
      case p: String => state += p
    }

    override def receiveCommand = {
      case "snap" =>
        saveSnapshot(state)
      case SaveSnapshotSuccess(md) =>
        probe ! md
      case p: String =>
        persist(p) {  state += _ }
    }
  }

  "A replayer" must {
    "produce a discrete stream of journaled messages" in {
      val p = system.actorOf(Props(new TestPersistentActor("p1")))
      1 to 3 foreach { i => p ! i.toString }
      replay("p1").take(3).runLog.unsafePerformSync should be(Seq(Event("p1", 1L, "1"), Event("p1", 2L, "2"), Event("p1", 3L, "3")))
    }
    "produce a discrete stream of journaled messages from user-defined sequence number" in {
      val p = system.actorOf(Props(new TestPersistentActor("p2")))
      1 to 3 foreach { i => p ! i.toString }
      replay("p2", 2L).take(2).runLog.unsafePerformSync should be(Seq(Event("p2", 2L, "2"), Event("p2", 3L, "3")))
    }
  }

  "A journal" must {
    "journal a stream of messages" in {
      Process("a", "b", "c").journal("p3").run.unsafePerformSync
      replay("p3").map(p => (p.data, p.sequenceNr)).take(3).runLog.unsafePerformSync should be(Seq(("a", 1L), ("b", 2L), ("c", 3L)))
    }
  }

  "A snapshot loader" must {
    "produce the most recent snapshot" in {
      val p = system.actorOf(Props(new TestPersistentActor("p4")))
      p ! "a"
      p ! "b"
      p ! "snap"

      val metadata = expectMsgPF() { case md: SnapshotMetadata => md }
      snapshot[String]("p4").runLog.unsafePerformSync should be(Seq(Snapshot(metadata, "ab")))
    }
    "produce a zero snapshot if there's no snapshot stored" in {
      snapshot[String]("p5").runLog.unsafePerformSync should be(Seq(Snapshot(SnapshotMetadata("p5", 0L, 0L), "")))
    }
  }

  "A composition of snapshot and replay" must {
    "produce a discrete stream of updated states" in {
      val p = system.actorOf(Props(new TestPersistentActor("p6")))
      p ! "a"
      p ! "b"
      p ! "snap"
      p ! "c"
      p ! "d"
      expectMsgPF() { case md: SnapshotMetadata => md }

      val c = for {
        s @ Snapshot(meta, data) <- snapshot[String]("p6")
        state <- replay(meta.persistenceId, s.nextSequenceNr).map(_.data).scan(data)((acc,p) => acc + p)
      } yield state

      c.take(3).runLog.unsafePerformSync should be(Seq("ab", "abc", "abcd"))
    }
  }
}
