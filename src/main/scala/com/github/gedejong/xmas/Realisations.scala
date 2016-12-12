package com.github.gedejong.xmas

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, ThrottleMode}
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Success, Try}

case class FeatureCollection(`type`: String, srid: Int, features: Seq[Feature])

case class Feature(`type`: String, srid: Int, geometry: Point, properties: FeatureProps)

case class Point(`type`: String, coordinates: Seq[Double])

case class Coordinates(lat: Double, lon: Double) {
  def apply(coordinates: Seq[Double]) = Coordinates(coordinates(1), coordinates(0))
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
  implicit val system = ActorSystem("xmas-fetch-system")
  implicit val executionContext = system.dispatcher
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))
  import scala.concurrent.duration._
  val connectionPool: Flow[(HttpRequest, Any), (Try[HttpResponse], Any), HostConnectionPool] =
    Http().newHostConnectionPoolHttps[Any](host = "dct-api-development.simacan.com")


  val fetchFeatures: Source[FeatureCollection, (Cancellable, HostConnectionPool)] = {
    Source.tick(0.second, 5.second, (HttpRequest(
        method = HttpMethods.GET,
        uri = s"https://dct-api-development.simacan.com/api/geo/${XMasConfig.retailer}/client/realisatie",
        headers = immutable.Seq(RawHeader("Security-Token", XMasConfig.token))), None))
      .log("realisation-tick", identity)
      .viaMat(connectionPool)(Keep.both)
      .log("request-result", identity)
      .collect { case (Success(response), _) => Unmarshal(response.entity).to[FeatureCollection] }
      .mapAsync(1)(identity)
  }

  import scala.concurrent.duration._

  val realisationsFlow: Source[Feature, (Cancellable, HostConnectionPool)] =
      fetchFeatures
      .filter(fc => !fc.features.isEmpty)
      .log("features", identity)
          .map { fc =>
            val maxTimestamp = fc.features.map(f => f.properties.timestamp).max
            fc.copy(features = fc.features.filter(f => f.properties.timestamp > maxTimestamp - (5 * 1000)))
          }
      .mapConcat[Feature](features => features.features.to[scala.collection.immutable.Seq])
      .throttle(1, 100.millis, 1, ThrottleMode.shaping)
      .log("feature", identity)
}
