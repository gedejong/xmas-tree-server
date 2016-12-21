package com.github.gedejong.xmas

case class LedCoord(led: Int, coord: PseudoMercator)

object LedCoordMapping {
  import CoordOps._
  import MapValue._

  val minLatLon = LatLonDeg(51f, 4.5f)
  val maxLatLon = LatLonDeg(53f, 7f)

  private type XY = (Double, Double)

  private[this] val ledPoints: Seq[XY] = Stream(
    (60, 51), (45, 40), (25, 44), (0, 61),
    (-20, 46), (-40, 43), (-83, 47), (-90, 39),
    (-120, 43), (-160, 47), (-180, 58), (135, 59),
    (92, 65), (45, 69), (30, 66), (-20, 76),
    (-55, 71), (-95, 66), (-145, 63), (-180, 76),
    (140, 80), (90, 80), (50, 82), (20, 90),
    (-20, 92), (-70, 89), (-115, 89), (-150, 90),
    (175, 95), (160, 98), (100, 110), (65, 116),
    (48, 112), (-15, 120), (-50, 130), (-135, 137),
    (-180, 126), (150, 126), (115, 145), (45, 149),
    (-2, 150), (-33, 150), (45, 140), (40, 114),
    (45, 85), (80, 61)
  )

  val coordToLedMapping: Seq[LedCoord] = buildCoordToLedMappingFromPoints(ledPoints)

  def buildCoordToLedMappingFromPoints(ledPoints: Seq[XY]): Seq[LedCoord] = {
    val rawCoordToLedMapping = ledPoints.zipWithIndex
    val rawMinX = rawCoordToLedMapping.map(_._1._1).min
    val rawMinY = rawCoordToLedMapping.map(_._1._2).min
    val rawMaxX = rawCoordToLedMapping.map(_._1._1).max
    val rawMa = rawCoordToLedMapping.map(_._1._2).max

    val zoomLevel = 0.0
    val boundsBottomLeft = minLatLon.toPseudoMercator(zoomLevel)
    val boundsTopRight = maxLatLon.toPseudoMercator(zoomLevel)

    rawCoordToLedMapping.map { case ((x, y), led) =>
      val coordX = mapValueToBounds(x, rawMinX, rawMaxX, boundsBottomLeft.x, boundsTopRight.x)
      val coordY = mapValueToBounds(y, rawMinY, rawMa, boundsTopRight.y, boundsBottomLeft.y)
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

  val ledCount = coordToLedMapping.length
}
