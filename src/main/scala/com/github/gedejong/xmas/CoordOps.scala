package com.github.gedejong.xmas

import Math._

case class LatLonDeg(lat: Double, lon: Double)
case class LatLonRad(lat: Double, lon: Double)
case class Mercator(x: Double, y: Double)
case class PseudoMercator(x: Double, y: Double, zoomLevel: Double)

object CoordOps {
  val HALF_PI = PI / 2.0
  val QUARTER_PI = PI / 4.0

  implicit class LatLonDegOps(latLon: LatLonDeg) {
    def toLatLonRad: LatLonRad = LatLonRad(toRadians(latLon.lat), toRadians(latLon.lon))
    def toMercator: Mercator = toLatLonRad.toMercator
    def toPseudoMercator(zoomLevel: Double = 0.0): PseudoMercator = toMercator.toPseudoMercator(zoomLevel)
  }

  implicit class LatLonRadOps(latLon: LatLonRad) {
    def toLatLonDeg: LatLonDeg = LatLonDeg(toDegrees(latLon.lat), toDegrees(latLon.lon))
    def toMercator: Mercator = Mercator(latLon.lon, log(tan(QUARTER_PI + latLon.lat / 2)))
    def toPseudoMercator(zoomLevel: Double = 0.0): PseudoMercator = toMercator.toPseudoMercator(zoomLevel)
  }

  implicit class MercatorOps(mercator: Mercator) {
    def toLatLonDeg: LatLonDeg = toLatLonRad.toLatLonDeg
    def toLatLonRad: LatLonRad = LatLonRad(atan(exp(mercator.y)) * 2 - HALF_PI, mercator.x)
    def toPseudoMercator(zoomLevel: Double = 0.0): PseudoMercator = {
      val zoom = 128.0 / PI * pow(2, zoomLevel)
      val cutoffY = min(max(mercator.y, -Math.PI), Math.PI)
      PseudoMercator(zoom * (mercator.x + Math.PI), zoom * (PI - cutoffY), zoomLevel)
    }
  }
}
