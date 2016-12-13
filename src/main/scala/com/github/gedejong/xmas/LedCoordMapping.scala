package com.github.gedejong.xmas

case class LedCoord(led: Int, coord: PseudoMercator)

object LedCoordMapping {
  import CoordOps._
  import MapValue._

  val minLatLon = LatLonDeg(51f, 4.5f)
  val maxLatLon = LatLonDeg(53f, 7f)

  private[this] case class XY(x: Double, y: Double)

  private[this] def ledPoints: Seq[XY] = Seq(
    XY(60, 51),
    XY(45, 40),
    XY(25, 44),
    XY(0, 61),
    XY(-20, 46),
    XY(-40, 43),
    XY(-83, 47),
    XY(-90, 39),
    XY(-120, 43),
    XY(-160, 47),
    XY(-180, 58),
    XY(135, 59),
    XY(92, 65),
    XY(45, 69),
    XY(30, 66),
    XY(-20, 76),
    XY(-55, 71),
    XY(-95, 66),
    XY(-145, 63),
    XY(-180, 76),
    XY(140, 80),
    XY(90, 80),
    XY(50, 82),
    XY(20, 90),
    XY(-20, 92),
    XY(-70, 89),
    XY(-115, 89),
    XY(-150, 90),
    XY(175, 95),
    XY(160, 98),
    XY(100, 110),
    XY(65, 116),
    XY(48, 112),
    XY(-15, 120),
    XY(-50, 130),
    XY(-135, 137),
    XY(-180, 126),
    XY(150, 126),
    XY(115, 145),
    XY(45, 149),
    XY(-2, 150),
    XY(-33, 150),
    XY(45, 140),
    XY(40, 114),
    XY(45, 85),
    XY(80, 61)
  )

  val coordToLedMapping: Seq[LedCoord] = buildCoordToLedMappingFromPoints(ledPoints)

  def buildCoordToLedMappingFromPoints(ledPoints: Seq[XY]): Seq[LedCoord] = {
    val rawCoordToLedMapping = ledPoints.zipWithIndex
    val rawMinX = rawCoordToLedMapping.map(_._1.x).min
    val rawMinY = rawCoordToLedMapping.map(_._1.y).min
    val rawMaxX = rawCoordToLedMapping.map(_._1.x).max
    val rawMaxY = rawCoordToLedMapping.map(_._1.y).max

    val zoomLevel = 0.0
    val boundsBottomLeft = minLatLon.toPseudoMercator(zoomLevel)
    val boundsTopRight = maxLatLon.toPseudoMercator(zoomLevel)

    rawCoordToLedMapping.map { case (XY(x, y), led) =>
      val coordX = mapValueToBounds(x, rawMinX, rawMaxX, boundsBottomLeft.x, boundsTopRight.x)
      val coordY = mapValueToBounds(y, rawMinY, rawMaxY, boundsTopRight.y, boundsBottomLeft.y)
      LedCoord(led, PseudoMercator(coordX, coordY, zoomLevel))
    }.sortBy(lc => lc.coord.y -> lc.coord.x)
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

  def ledCount = coordToLedMapping.length
}
