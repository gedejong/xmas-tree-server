package com.github.gedejong.xmas

import java.awt.Color

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import ch.jodersky.flow.SerialSettings
import ch.jodersky.flow.stream.Serial
import ch.jodersky.flow.stream.Serial.Connection
import scodec.Attempt
import scodec.bits.ByteVector
import shapeless.{:+:, CNil}

import scala.concurrent.Future

object AkkaScodecInterop {

  implicit class EnrichedByteString(val value: ByteString) extends AnyVal {
    def toByteVector: ByteVector = ByteVector.viewAt((idx: Long) => value(idx.toInt), value.size.toLong)
  }

  implicit class EnrichedByteVector(val value: ByteVector) extends AnyVal {
    def toByteString: ByteString = ByteString(value.toArray)
  }

}

/**
  * Created by edejong on 08-12-2016.
  */

trait TreeModel {

  case class SetLed(ledNr: Int, color: Color)

  case class SetFlicker(flicker: Int)

  type TreeCommand = SetLed :+: SetFlicker :+: CNil
}

trait TreeCodec extends TreeModel {

  import scodec._
  import codecs._

  val rgbCodec: Codec[Color] = (uint8 ~ uint8 ~ uint8)
    .xmap(
      { case ((r, g), b) => new Color(r, g, b) },
      (color: Color) => ((color.getRed, color.getGreen), color.getBlue))

  import play.api.libs.functional.syntax._
  val setLedCodec: Codec[SetLed] = (uint8 ~ rgbCodec).
    xmap(SetLed, unlift(SetLed.unapply))
  val setFlickerCodec: Codec[SetFlicker] = uint8.as[SetFlicker]

  val treeCommandCodec: Codec[TreeCommand] with KnownDiscriminatorType[Int] =
    (setLedCodec :+: setFlickerCodec).discriminatedByIndex(uint8)
}

trait TreeFormat extends TreeModel {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._

  val colorFormatObject: Format[Color] = (
    (__ \ "red").format[Int] and
      (__ \ "green").format[Int] and
      (__ \ "blue").format[Int]
  )(
    { case (r, g, b) => new Color(r, g, b)},
    c => (c.getRed, c.getGreen, c.getBlue))

  val colorFormatHex: Reads[Color] = Reads.of[String].map(Color.decode)

  val colorReads = colorFormatHex orElse colorFormatObject

  val setFlickerFormat = Format.of[Int].inmap(SetFlicker, unlift(SetFlicker.unapply))

  val setLedFormat: Format[SetLed] = (
    (__ \ "led").format[Int] and
      (__ \ "color").format(Format(colorReads, colorFormatObject))
  )(SetLed, unlift(SetLed.unapply))
}

object TreeControl extends TreeModel with TreeCodec {
  import AkkaScodecInterop._

  def treeBinary(implicit actorSystem: ActorSystem): Flow[ByteString, ByteString, Future[Connection]] =
    Serial().open("/dev/ttyp3", SerialSettings(baud = 9600))

  val treeCommandEncoder: Flow[TreeCommand, ByteString, NotUsed] =
    Flow[TreeCommand]
      .map(tc => treeCommandCodec.encode(tc).map(_.toByteVector.toByteString))
      .collect { case Attempt.Successful(bs) => bs }



}
