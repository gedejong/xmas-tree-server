package com.github.gedejong.xmas

import CoordOps._

case class LedCoord(led: Int, coord: PseudoMercator)

object LedCoordMapping {
  val minLatLon = LatLonDeg(51f, 4.5f)
  val maxLatLon = LatLonDeg(53f, 7f)
  val ledCount = ledPoints.length

  private[this] case class XY(x: Double, y: Double)

  private[this] def ledPoints: Seq[XY] = Seq(
    XY(0, 0)
  )  // TODO Insert values here

  val coordToLedMapping: Seq[LedCoord] = buildCoordToLedMappingFromPoints(ledPoints)

  def buildCoordToLedMappingFromPoints(ledPoints: Seq[XY]): Seq[LedCoord] = {
    val rawCoordToLedMapping = ledPoints.zipWithIndex
    val rawMinX = rawCoordToLedMapping.map(_._1.x).min
    val rawMinY = rawCoordToLedMapping.map(_._1.y).max
    val rawMaxX = rawCoordToLedMapping.map(_._1.x).min
    val rawMaxY = rawCoordToLedMapping.map(_._1.y).max
    val rawDeltaX = rawMaxX - rawMinX
    val rawDeltaY = rawMaxY - rawMinY

    val zoomLevel = 0.0
    val boundsBottomLeft = minLatLon.toPseudoMercator(zoomLevel)
    val boundsTopRight = maxLatLon.toPseudoMercator(zoomLevel)
    val boundsDeltaX = boundsTopRight.x - boundsBottomLeft.x
    val boundsDeltaY = boundsBottomLeft.y - boundsTopRight.y

    rawCoordToLedMapping.map { lc =>
      val coordX = boundsDeltaX * (lc._1.x - rawMinX) * rawDeltaX
      val coordY = boundsDeltaY * (lc._1.y - rawMinY) * rawDeltaY
      LedCoord(lc._2, PseudoMercator(coordX, coordY, zoomLevel))
    }.sortBy(_.coord.y)
  }

  def coordToLed(coord: Coordinates): Int = {
    mercatorToLed(coordToMercator(coord))
  }

  def coordToMercator(coord: Coordinates): PseudoMercator = {
    import Math._
    val clampedLat = min(max(coord.lat, minLatLon.lat), maxLatLon.lat)
    val clampedLon = min(max(coord.lon, minLatLon.lon), maxLatLon.lon)
    LatLonDeg(clampedLat, clampedLon).toPseudoMercator()
  }

  def mercatorToLed(mercator: PseudoMercator): Int = {
    coordToLedMapping.map { lc =>
      val lcMercator = lc.coord
      val xDelta = lcMercator.x - mercator.x
      val yDelta = lcMercator.y - mercator.y
      val distanceSquared = xDelta * xDelta + yDelta * yDelta
      (lc, distanceSquared)
    }.minBy(_._2)._1.led
  }
}
