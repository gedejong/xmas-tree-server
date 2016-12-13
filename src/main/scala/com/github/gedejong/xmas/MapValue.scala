package com.github.gedejong.xmas

object MapValue {
  def mapValueToBounds(inValue: Double, inBoundsStart: Double, inBoundsEnd: Double, outBoundsStart: Double, outBoundsEnd: Double): Double = {
    mapValueToArea(inValue, inBoundsStart, inBoundsEnd - inBoundsStart, outBoundsStart, outBoundsEnd - outBoundsStart)
  }

  def mapValueToArea(inValue: Double, inAreaStart: Double, inAreaDelta: Double, outAreaStart: Double, outAreaDelta: Double): Double = {
    (inValue - inAreaStart) / inAreaDelta * outAreaDelta + outAreaStart
  }
}
