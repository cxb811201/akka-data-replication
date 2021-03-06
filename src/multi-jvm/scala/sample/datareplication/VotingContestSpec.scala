/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.datareplication

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.contrib.datareplication.DataReplication
import akka.contrib.datareplication.Flag
import akka.contrib.datareplication.PNCounterMap
import akka.contrib.datareplication.Replicator.GetReplicaCount
import akka.contrib.datareplication.Replicator.ReplicaCount
import akka.contrib.datareplication.STMultiNodeSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object VotingContestSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    """))

}

object VotingService {
  case object Open
  case object OpenAck
  case object Close
  case object CloseAck
  final case class Vote(participant: String)
  case object GetVotes
  final case class Votes(result: Map[String, Long], open: Boolean)

  private final case class GetVotesReq(replyTo: ActorRef)
}

class VotingService extends Actor {
  import akka.contrib.datareplication.Replicator._
  import VotingService._

  val replicator = DataReplication(context.system).replicator
  implicit val cluster = Cluster(context.system)
  val OpenedKey = "contestOpened"
  val ClosedKey = "contestClosed"
  val CountersKey = "contestCounters"

  replicator ! Subscribe(OpenedKey, self)

  def receive = {
    case Open ⇒
      replicator ! Update(OpenedKey, Flag(), WriteAll(5.seconds))(_.switchOn)
      becomeOpen()

    case Changed(OpenedKey, flag: Flag) if flag.enabled ⇒
      becomeOpen()

    case GetVotes ⇒
      sender() ! Votes(Map.empty, open = false)
  }

  def becomeOpen(): Unit = {
    replicator ! Unsubscribe(OpenedKey, self)
    replicator ! Subscribe(ClosedKey, self)
    context.become(open orElse getVotes(open = true))
  }

  def open: Receive = {
    case v @ Vote(participant) ⇒
      val update = Update(CountersKey, PNCounterMap(), WriteLocal, request = Some(v)) {
        _.increment(participant, 1)
      }
      replicator ! update

    case _: UpdateSuccess ⇒

    case Close ⇒
      replicator ! Update(ClosedKey, Flag(), WriteAll(5.seconds))(_.switchOn)
      context.become(getVotes(open = false))

    case Changed(ClosedKey, flag: Flag) if flag.enabled ⇒
      context.become(getVotes(open = false))
  }

  def getVotes(open: Boolean): Receive = {
    case GetVotes ⇒
      replicator ! Get(CountersKey, ReadAll(3.seconds), Some(GetVotesReq(sender())))

    case GetSuccess(CountersKey, d: PNCounterMap, Some(GetVotesReq(replyTo))) ⇒
      replyTo ! Votes(d.entries, open)

    case NotFound(CountersKey, Some(GetVotesReq(replyTo))) ⇒
      replyTo ! Votes(Map.empty, open)

    case _: GetFailure    ⇒

    case _: UpdateSuccess ⇒
  }

}

class VotingContestSpecMultiJvmNode1 extends VotingContestSpec
class VotingContestSpecMultiJvmNode2 extends VotingContestSpec
class VotingContestSpecMultiJvmNode3 extends VotingContestSpec

class VotingContestSpec extends MultiNodeSpec(VotingContestSpec) with STMultiNodeSpec with ImplicitSender {
  import VotingContestSpec._
  import ShoppingCart._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Demo of a replicated voting" must {

    "join cluster" in within(10.seconds) {
      join(node1, node1)
      join(node2, node1)
      join(node3, node1)

      awaitAssert {
        DataReplication(system).replicator ! GetReplicaCount
        expectMsg(ReplicaCount(roles.size))
      }
      enterBarrier("after-1")
    }

    "count votes correctly" in within(15.seconds) {
      import VotingService._
      val votingService = system.actorOf(Props[VotingService], "votingService")
      val N = 1000
      runOn(node1) {
        votingService ! Open
        for (n ← 1 to N) {
          votingService ! Vote("#" + ((n % 20) + 1))
        }
      }
      runOn(node2, node3) {
        // wait for it to open
        val p = TestProbe()
        awaitAssert {
          votingService.tell(GetVotes, p.ref)
          p.expectMsgPF(3.seconds) { case Votes(_, true) ⇒ true }
        }
        for (n ← 1 to N) {
          votingService ! Vote("#" + ((n % 20) + 1))
        }
      }
      enterBarrier("voting-done")
      runOn(node3) {
        votingService ! Close
      }

      val expected = (1 to 20).map(n ⇒ "#" + n -> (3L * N / 20)).toMap
      awaitAssert {
        votingService ! GetVotes
        expectMsg(3.seconds, Votes(expected, false))
      }

      enterBarrier("after-2")
    }
  }

}

