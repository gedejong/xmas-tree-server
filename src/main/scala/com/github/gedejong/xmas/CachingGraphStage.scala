package com.github.gedejong.xmas

import akka.stream._
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Source}
import akka.stream.stage._

import scala.collection.immutable.Seq
import scala.collection.mutable

object ExtraFlow {

  implicit class SourceCaching[In, Mat](s: Source[In, Mat]) {
    def cache[Out2, Mat2](cacheFlow: Flow[In, (In, Out2), Mat2]): Source[Out2, Mat] =
      cacheMat(cacheFlow)(Keep.left)

    def cacheMat[Out2, Mat2, Mat3](cacheFlow: Flow[In, (In, Out2), Mat2])(combineMat: (Mat, Mat2) => Mat3): Source[Out2, Mat3] = {
      Source.fromGraph(
        GraphDSL.create(s, cacheFlow)(combineMat) { implicit b =>
          (s2: Source[In, Mat]#Shape, cacheFlow2: Flow[In, (In, Out2), Mat2]#Shape) =>
            import GraphDSL.Implicits._
            val cachingGraphStage = b.add(new CachingGraphStage[In, Out2])
            s2.out ~> cachingGraphStage.requestIn
            cachingGraphStage.requestCacheIn ~> cacheFlow2.in
            cacheFlow2.out ~> cachingGraphStage.responseCacheOut
            SourceShape(cachingGraphStage.responseOut)
        })
    }
  }

  implicit class FlowCaching[In, Out, Mat](s: Flow[In, Out, Mat]) {
    def cache[Out2](cacheFlow: Flow[Out, (Out, Out2), Mat]) = {
      Flow.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val cachingGraphStage = new CachingGraphStage[Out, Out2]
        cachingGraphStage.requestCache ~> cacheFlow ~> cachingGraphStage.responseCache
        FlowShape(cachingGraphStage.in, cachingGraphStage.out)
      })
    }
  }

}

case class CachingShape[A, B](
                               requestIn: Inlet[A],
                               responseOut: Outlet[B],
                               requestCacheIn: Outlet[A],
                               responseCacheOut: Inlet[(A, B)]
                             ) extends Shape {

  override def inlets: Seq[Inlet[_]] = Seq(requestIn, responseCacheOut)

  override def outlets: Seq[Outlet[_]] = Seq(responseOut, requestCacheIn)

  override def deepCopy(): Shape = CachingShape(
    requestIn = requestIn.carbonCopy(),
    responseOut = responseOut.carbonCopy(),
    requestCacheIn = requestCacheIn.carbonCopy(),
    responseCacheOut = responseCacheOut.carbonCopy()
  )

  // A Shape must also be able to create itself from existing ports
  override def copyFromPorts(
                              inlets: Seq[Inlet[_]],
                              outlets: Seq[Outlet[_]]): Shape = {
    assert(inlets.size == this.inlets.size)
    assert(outlets.size == this.outlets.size)
    // This is why order matters when overriding inlets and outlets.
    CachingShape[A, B](inlets(0).as[A], outlets(0).as[B], outlets(1).as[A], inlets(1).as[(A, B)])
  }
}

class CachingGraphStage[A, B] extends GraphStage[CachingShape[A, B]] {
  val out: Outlet[B] = Outlet("results")
  val in: Inlet[A] = Inlet("requests")
  val requestCache: Outlet[A] = Outlet("request-cache")
  val responseCache: Inlet[(A, B)] = Inlet("response-cache")

  override val shape: CachingShape[A, B] = new CachingShape[A, B](in, out, requestCache, responseCache)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      val cached = mutable.Map[A, B]()
      val waiting = mutable.Set[A]()
      var cacheLineWaiting: Option[A] = None
      var outWaiting: Option[B] = None

      setHandler(shape.requestIn, new InHandler {
        override def onPush(): Unit = {
          val a = grab(shape.requestIn)

          log.debug("Grabbed: {}", a)
          if (cached.contains(a)) {
            log.debug("Is already cached: {}", a)
            if (isAvailable(shape.responseOut)) {
              push(shape.responseOut, cached(a))
            } else {
              outWaiting = Some(cached(a))
            }
          } else if (!waiting(a)) {
            log.debug("Is not yet cached, and not waiting: {}", a)
            if (isAvailable((shape.requestCacheIn))) {
              log.debug("Pushing: {}", a)
              push(shape.requestCacheIn, a)
            } else {
              log.debug("CacheLineWaiting: {}", a)
              cacheLineWaiting = Some(a)
            }
          }
        }
      })

      setHandler(shape.responseCacheOut, new InHandler {
        override def onPush() = {
          val (a, b) = grab(shape.responseCacheOut)
          log.debug("Retreived key-value: {} -> {}", a, b)
          waiting -= a
          cached += a -> b
          if (isAvailable(shape.responseOut)) {
            log.debug("Pushing: {}", b)
            push(shape.responseOut, b)
          } else {
            log.debug("OutWaiting : {}", b)
            outWaiting = Some(b)
          }
        }
      })

      setHandler(shape.requestCacheIn, new OutHandler {
        override def onPull(): Unit = {
          cacheLineWaiting match {
            case Some(a) =>
              push(shape.requestCacheIn, a)
              cacheLineWaiting = None
            case None =>
              pull(shape.requestIn)
          }
        }
      })

      setHandler(shape.responseOut, new OutHandler {
        override def onPull(): Unit =
          outWaiting match {
            case Some(b) =>
              push(shape.responseOut, b)
              outWaiting = None
            case None =>
              pull(shape.responseCacheOut)
          }
      })

    }
}

