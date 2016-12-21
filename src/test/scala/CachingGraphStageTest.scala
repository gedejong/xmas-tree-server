import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, ClosedShape, OverflowStrategy, SourceShape}
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.testkit.{ImplicitSender, TestActors, TestKit}
import com.github.gedejong.xmas.CachingGraphStage
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by edejong on 21-12-2016.
  */
class CachingGraphStageTest() extends TestKit(ActorSystem("MySpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An CachingGraphStage" must {

    "using a simple caching actor be able to return the value" in {
      import com.github.gedejong.xmas.ExtraFlow._
      val lengthFlow: Flow[String, (String, Int), NotUsed] = Flow[String].map(str => (str, str.length) )

      implicit val materializer = ActorMaterializer()
      val outerSelf = self

      val source = Source.actorRef[String](1000, OverflowStrategy.fail)
      val g = RunnableGraph.fromGraph(
        GraphDSL.create(source) { implicit b => s =>
          import akka.stream.scaladsl.GraphDSL.Implicits._
          val cachingGraphStage = b.add(new CachingGraphStage[String, Int])
          s ~> cachingGraphStage.requestIn
          cachingGraphStage.requestCacheIn ~> lengthFlow ~> cachingGraphStage.responseCacheOut
          cachingGraphStage.responseOut ~> Sink.actorRef(outerSelf, None)
          ClosedShape
        })
      val actorRef = g.run()

      actorRef ! "test"
      actorRef ! "test"
      expectMsg(4)
      expectMsg(4)

    }

  }
}
