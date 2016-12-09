package com.github.gedejong.xmas

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.concurrent.Future

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
  implicit val featureCollectionFormat: Format[FeatureCollection] = (
    (__ \ "type").format[String] and
      (__ \ "srid").format[Int] and
      (__ \ "features").format[Seq[Feature]]
    )(FeatureCollection.apply, unlift(FeatureCollection.unapply))

  implicit val featureFormat: Format[Feature] = (
    (__ \ "type").format[String] and
      (__ \ "srid").format[Int] and
      (__ \ "geometry").format[Point] and
      (__ \ "properties").format[FeatureProps]
    )(Feature.apply, unlift(Feature.unapply))

  implicit val pointFormat: Format[Point] = (
    (__ \ "type").format[String] and
      (__ \ "coordinates").format[Seq[Double]]
    )(Point.apply, unlift(Point.unapply))

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
      (__ \ "ritId").format[String]and
      (__ \ "selected").formatNullable[Boolean] and
      (__ \ "speed").formatNullable[Double] and
      (__ \ "timestamp").format[Long] and
      (__ \ "tractorType").formatNullable[String] and
      (__ \ "transporteurNaam").formatNullable[String] and
      (__ \ "vehicleId").formatNullable[String]
    )(FeatureProps.apply, unlift(FeatureProps.unapply))
}

object Realisations extends PlayJsonSupport with FeatureFormats {
  implicit val system = ActorSystem("xmas-fetch-system")
  implicit val executionContext = system.dispatcher
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  def fetchFeatures(): Future[FeatureCollection] = {
    for {
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = s"https://dct-api-development.simacan.com/api/geo/ahold/client/realisatie"))
      entity <- Unmarshal(response.entity).to[FeatureCollection]
    } yield entity
  }
}
