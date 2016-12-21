import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, DelayOverflowStrategy, OverflowStrategy}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, Inspectors, Matchers, WordSpecLike}

/**
  * Created by edejong on 21-12-2016.
  */
class CachingGraphStageTest() extends TestKit(ActorSystem("MySpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with Inspectors{

  import com.github.gedejong.xmas.ExtraFlow._
  import scala.concurrent.duration._
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An CachingGraphStage" must {

    val slowRequester = Flow[String]
      .buffer(10000, OverflowStrategy.fail)
      .log("buffer")
      .delay(100.millis, DelayOverflowStrategy.backpressure).map(str => (str, str.length))
      .log("delay")

    val testGraph = Source.actorRef[String](10000, OverflowStrategy.fail)
      .cache(slowRequester)
      .to(Sink.actorRef(self, None))

    "basic logic" in {

      val actorRef = testGraph.run

      actorRef ! "test"
      within(100.millis, 150.millis) { expectMsg(4) } // First is not cached

      actorRef ! "testtest"
      within(100.millis, 150.millis) { expectMsg(8) } // Second is not cached

      actorRef ! "testtest"
      within(0.millis, 10.millis) { expectMsg(8) } // Third should return immediately

      actorRef ! "test"
      within(0.millis, 10.millis) { expectMsg(4) } // Fourth should return immediately
    }

    "stress test, all (but one) cached" in {

      val actorRef = testGraph.run

      // Send a thousand times test. Should cache first and return quickly afterwards
      for (i <- 0 to 5000) actorRef ! "test"

      within(100.millis, 1000.millis) {
        val receivedInts = receiveN(5000).map(_.asInstanceOf[Int])
        receivedInts.length should be (5000)
        all (receivedInts) should be (4)
      } // First is not cached
    }

    "stress test, nothing cached" in {

      val actorRef = testGraph.run

      // Send a thousand times test. Should cache first and return quickly afterwards
      val n = 50
      for (i <- 0 to n) actorRef ! i.toString

      within(100.millis, 6000.millis) {
        // Should take around 5 seconds to get everything from cache
        val receivedInts = receiveN(n).map(_.asInstanceOf[Int])
        receivedInts.length should be (n)
      } // First is not cached
    }
  }
}
