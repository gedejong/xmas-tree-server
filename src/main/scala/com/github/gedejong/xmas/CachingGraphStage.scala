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
          (s2, cacheFlow2) =>
            import GraphDSL.Implicits._
            val cachingGraphStage = b.add(new CachingGraphStage[In, Out2])
            s2 ~> cachingGraphStage.requestIn
            cachingGraphStage.requestCacheIn ~> cacheFlow2 ~> cachingGraphStage.responseCacheOut
            SourceShape(cachingGraphStage.responseOut)
        })
    }
  }

  implicit class FlowCaching[In, Out, Mat](flow: Flow[In, Out, Mat]) {
    def cache[Out2, Mat2](cacheFlow: Flow[Out, (Out, Out2), Mat2]): Flow[In, Out2, Mat] = cacheMat(cacheFlow)(Keep.left)

    def cacheMat[Out2, Mat2, Mat3](cacheFlow: Flow[Out, (Out, Out2), Mat2])(combineMat: (Mat, Mat2) => Mat3): Flow[In, Out2, Mat3] = {
      Flow.fromGraph(
        GraphDSL.create(flow, cacheFlow)(combineMat) { implicit b =>
          (flow2, cacheFlow2) =>
            import GraphDSL.Implicits._
            val cachingGraphStage = b.add(new CachingGraphStage[Out, Out2])
            flow2.out ~> cachingGraphStage.requestIn
            cachingGraphStage.requestCacheIn ~> cacheFlow2 ~> cachingGraphStage.responseCacheOut
            FlowShape(flow2.in, cachingGraphStage.responseOut)
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
    CachingShape[A, B](
      requestIn = inlets.head.as[A],
      responseOut = outlets.head.as[B],
      requestCacheIn = outlets(1).as[A],
      responseCacheOut = inlets(1).as[(A, B)])
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
      val cached: mutable.Map[A, B] = mutable.Map[A, B]()
      var waitingForCacheLineResult: List[A] = List[A]()
      var cacheLineWaiting: mutable.Queue[A] = mutable.Queue()
      var outQueue: mutable.Queue[B] = mutable.Queue()

      setHandler(shape.requestIn, new InHandler {
        override def onUpstreamFinish() = {
          checkForComplete()
        }

        override def onPush(): Unit = {
          val a = grab(shape.requestIn)

          log.debug("Grabbed: {}", a)
          if (cached.contains(a)) {
            log.debug("Is already cached: {}", a)
            outQueue.enqueue(cached(a))
            if (isAvailable(shape.responseOut)) {
              push(shape.responseOut, outQueue.dequeue())
            }
          } else if (!waitingForCacheLineResult.contains(a)) {
            log.debug("Is not yet cached, and not waiting: {}", a)
            waitingForCacheLineResult = a :: waitingForCacheLineResult
            cacheLineWaiting.enqueue(a)
            if (isAvailable(shape.requestCacheIn)) {
              log.debug("Pushing to cacheline: {}", a)
              push(shape.requestCacheIn, cacheLineWaiting.dequeue())
            } else {
              log.debug("CacheLineWaiting: {}", a)
            }
          } else {
            waitingForCacheLineResult = a :: waitingForCacheLineResult
            log.debug("Is not yet cached, adding to waiting: {}", a)
          }
          pull(shape.requestIn)
        }
      })

      def checkForComplete(): Unit = {
        if (log.isDebugEnabled) {
          log.debug(
            s"Check for complete: ${waitingForCacheLineResult.size}, ${outQueue.size}, ${cacheLineWaiting.size}")
        }
        if (waitingForCacheLineResult.isEmpty &&
          outQueue.isEmpty &&
          cacheLineWaiting.isEmpty &&
          isClosed(shape.requestIn)) {

          log.debug("We are complete!")
          complete(shape.responseOut)
          complete(shape.requestCacheIn)
        }
      }

      setHandler(shape.responseCacheOut, new InHandler {
        override def onUpstreamFinish() = {
          checkForComplete()
        }

        override def onPush(): Unit = {
          val (a, b) = grab(shape.responseCacheOut)
          log.debug("Retrieved key-value: {} -> {}", a, b)
          waitingForCacheLineResult.filter(_ == a).foreach(_ => outQueue.enqueue(b))
          waitingForCacheLineResult = waitingForCacheLineResult.filter(_ != a)
          cached += a -> b

          if (isAvailable(shape.responseOut)) {
            log.debug("Pushing to out: {}", b)
            push(shape.responseOut, outQueue.dequeue())
          }
          checkForComplete()
          pull(shape.responseCacheOut)
        }
      })

      setHandler(shape.requestCacheIn, new OutHandler {
        override def onPull(): Unit = {
          if (cacheLineWaiting.nonEmpty) {
            push(shape.requestCacheIn, cacheLineWaiting.dequeue())
          }
          if (!hasBeenPulled(shape.requestIn))
            pull(shape.requestIn)
        }
      })

      setHandler(shape.responseOut, new OutHandler {
        override def onPull(): Unit = {
          if (outQueue.nonEmpty) {
            push(shape.responseOut, outQueue.dequeue())
            checkForComplete()
          } else {
            if (!hasBeenPulled(shape.requestIn) &&
              !isClosed(shape.requestIn)) {
              pull(shape.requestIn)
            }
            if (!hasBeenPulled(shape.responseCacheOut) &&
              !isClosed(shape.responseCacheOut)) {
              pull(shape.responseCacheOut)
            }
          }
        }
      })
    }
}

