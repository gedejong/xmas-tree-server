package com.github.gedejong.xmas

import CoordOps._

case class LedCoord(led: Int, coord: PseudoMercator)

object LedCoordMapping {
  val minLatLon = LatLonDeg(51f, 4.5f)
  val maxLatLon = LatLonDeg(53f, 7f)
  val ledCount = ledPoints.length

  case class XY(x: Double, y: Double)

  def ledPoints: Seq[XY] = Seq(
    XY(0, 0),
    XY(1, 1),
    XY(2, 2),
    XY(3, 3),
    XY(4, 4),
    XY(5, 5),
    XY(6, 6),
    XY(7, 7),
    XY(8, 8),
    XY(9, 9),
    XY(10, 10),
    XY(11, 11),
    XY(12, 12),
    XY(13, 13),
    XY(14, 14),
    XY(15, 15),
    XY(16, 16),
    XY(17, 17),
    XY(18, 18),
    XY(19, 19),
    XY(20, 20),
    XY(21, 21),
    XY(22, 22),
    XY(23, 23),
    XY(24, 24),
    XY(25, 25),
    XY(26, 26),
    XY(27, 27),
    XY(28, 28),
    XY(29, 29),
    XY(30, 30),
    XY(31, 31),
    XY(32, 32),
    XY(33, 33),
    XY(34, 34),
    XY(35, 35),
    XY(36, 36),
    XY(37, 37),
    XY(38, 38),
    XY(39, 39),
    XY(40, 40),
    XY(41, 41),
    XY(42, 42),
    XY(43, 43),
    XY(44, 44),
    XY(45, 45)
  )  // TODO Insert values here

  val coordToLedMapping: Seq[LedCoord] = buildCoordToLedMappingFromPoints(ledPoints)

  def buildCoordToLedMappingFromPoints(ledPoints: Seq[XY]): Seq[LedCoord] = {
    val rawCoordToLedMapping = ledPoints.zipWithIndex
    val rawMinX = rawCoordToLedMapping.map(_._1.x).min
    val rawMinY = rawCoordToLedMapping.map(_._1.y).min
    val rawMaxX = rawCoordToLedMapping.map(_._1.x).max
    val rawMaxY = rawCoordToLedMapping.map(_._1.y).max
    val rawDeltaX = rawMaxX - rawMinX
    val rawDeltaY = rawMaxY - rawMinY

    val zoomLevel = 0.0
    val boundsBottomLeft = minLatLon.toPseudoMercator(zoomLevel)
    val boundsTopRight = maxLatLon.toPseudoMercator(zoomLevel)
    val boundsDeltaX = boundsTopRight.x - boundsBottomLeft.x
    val boundsDeltaY = boundsTopRight.y - boundsBottomLeft.y

    val ret = rawCoordToLedMapping.map { case (XY(x, y), led) =>
      val coordX = boundsDeltaX * (x - rawMinX) / rawDeltaX + boundsBottomLeft.x
      val coordY = boundsDeltaY * (y - rawMinY) / rawDeltaY + boundsBottomLeft.y
      LedCoord(led, PseudoMercator(coordX, coordY, zoomLevel))
    }.sortBy(_.coord.y)
    println(ret)
    ret
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
    coordToLedMapping.minBy { lc =>
      val lcMercator = lc.coord
      val xDelta = lcMercator.x - mercator.x
      val yDelta = lcMercator.y - mercator.y
      xDelta * xDelta + yDelta * yDelta
    }.led
  }
}
