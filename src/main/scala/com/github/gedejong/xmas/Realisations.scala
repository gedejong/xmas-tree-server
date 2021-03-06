package com.github.gedejong.xmas

import akka.actor.{ActorSystem, Cancellable}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision, ThrottleMode}
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.collection.immutable
import scala.util.{Random, Success, Try}

case class FeatureCollection(`type`: String, srid: Int, features: Seq[Feature])

case class Feature(`type`: String, srid: Int, geometry: Point, properties: FeatureProps)

case class Point(`type`: String, coordinates: Seq[Double])

case class Coordinates(lat: Double, lon: Double)

object Coordinates {
  def fromPoint(point: Point) = fromCoords(point.coordinates)

  def fromCoords(coordinates: Seq[Double]) = Coordinates(coordinates(1), coordinates(0))
}

case class FeatureProps(
                         activityString: String,
                         amountStopsDelayed: Int,
                         driver: Option[String],
                         executingFleetNumberTractor: Option[String],
                         fuelType: Option[String],
                         geplandVlootNummerTractor: Option[String],
                         hasHeading: Option[Boolean],
                         heading: Option[Double],
                         huidigeVertragingInMinutes: Option[Int],
                         licensePlateTractor: Option[String],
                         lzv: Option[Boolean],
                         navigationDest: Option[String],
                         realisedStart: Option[String],
                         ritId: String,
                         selected: Option[Boolean],
                         speed: Option[Double],
                         timestamp: Long,
                         tractorType: Option[String],
                         transporteurNaam: Option[String],
                         vehicleId: Option[String]
                       )

trait FeatureFormats {
  implicit val pointFormat: Format[Point] = (
    (__ \ "type").format[String] and
      (__ \ "coordinates").format[Seq[Double]]
    ) (Point.apply, unlift(Point.unapply))

  implicit val featurePropsFormat: Format[FeatureProps] = (
    (__ \ "activityString").format[String] and
      (__ \ "amountStopsDelayed").format[Int] and
      (__ \ "driver").formatNullable[String] and
      (__ \ "executingFleetNumberTractor").formatNullable[String] and
      (__ \ "fuelType").formatNullable[String] and
      (__ \ "geplandVlootNummerTractor").formatNullable[String] and
      (__ \ "hasHeading").formatNullable[Boolean] and
      (__ \ "heading").formatNullable[Double] and
      (__ \ "huidigeVertragingInMinutes").formatNullable[Int] and
      (__ \ "licensePlateTractor").formatNullable[String] and
      (__ \ "lzv").formatNullable[Boolean] and
      (__ \ "navigationDest").formatNullable[String] and
      (__ \ "realisedStart").formatNullable[String] and
      (__ \ "ritId").format[String] and
      (__ \ "selected").formatNullable[Boolean] and
      (__ \ "speed").formatNullable[Double] and
      (__ \ "timestamp").format[Long] and
      (__ \ "tractorType").formatNullable[String] and
      (__ \ "transporteurNaam").formatNullable[String] and
      (__ \ "vehicleId").formatNullable[String]
    ) (FeatureProps.apply, unlift(FeatureProps.unapply))

  implicit val featureFormat: Format[Feature] = (
    (__ \ "type").format[String] and
      (__ \ "srid").format[Int] and
      (__ \ "geometry").format[Point] and
      (__ \ "properties").format[FeatureProps]
    ) (Feature.apply, unlift(Feature.unapply))

  implicit val featureCollectionFormat: Format[FeatureCollection] = (
    (__ \ "type").format[String] and
      (__ \ "srid").format[Int] and
      (__ \ "features").format[Seq[Feature]]
    ) (FeatureCollection.apply, unlift(FeatureCollection.unapply))

}

object Realisations extends PlayJsonSupport with FeatureFormats {
  import scala.concurrent.duration._

  private val pollingInterval = 20.second

  private val decider: Supervision.Decider = { e => Supervision.Restart }

  def connectionPool(implicit system: ActorSystem, materializer: ActorMaterializer): Flow[(HttpRequest, Any), (Try[HttpResponse], Any), HostConnectionPool] = {
    Http().newHostConnectionPoolHttps[Any](host = "dct-api-development.simacan.com")
  }


  def fetchFeatures(implicit system: ActorSystem): Source[FeatureCollection, (Cancellable, HostConnectionPool)] = {
    implicit val ec = system.dispatcher
    implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    Source.tick(0.second, pollingInterval, (HttpRequest(
      method = HttpMethods.GET,
      uri = s"https://dct-api-development.simacan.com/api/geo/${XMasConfig.retailer}/client/realisatie",
      headers = immutable.Seq(RawHeader("Security-Token", XMasConfig.token))), None))
      .viaMat(connectionPool)(Keep.both)
      .collect { case (Success(response), _) => Unmarshal(response.entity).to[FeatureCollection] }
      .mapAsync(1)(identity)
  }

  private val R = new Random()

  def realisationsFlow(implicit system: ActorSystem): Source[Feature, (Cancellable, HostConnectionPool)] =
    fetchFeatures
      .filter(fc => !fc.features.isEmpty)
        .sliding(2, 1)
      .map { case Seq(oldFc, newFc) =>
        val maxOldTimestamp = oldFc.features.map(f => f.properties.timestamp).max
        newFc.copy(features =
          newFc.features
            .filter(f => f.properties.timestamp > maxOldTimestamp)
            .map(f => f.copy(
              properties = f.properties.copy(
                timestamp = f.properties.timestamp - maxOldTimestamp + R.nextInt(pollingInterval.toMillis.toInt)))))
      }
      .mapConcat[Feature](features => features.features.to[scala.collection.immutable.Seq])
}
