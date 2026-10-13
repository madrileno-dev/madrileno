package madrileno.utils.imaging

import cats.effect.testing.scalatest.AsyncIOSpec
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.{GifWriter, PngWriter}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import scodec.bits.ByteVector

import java.awt.Color

class ImagingRotationSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val red  = Color.RED.getRGB
  private val blue = Color.BLUE.getRGB

  private val halves: ByteVector = {
    val image = ImmutableImage.filled(40, 20, Color.RED).map(p => if (p.x >= 20) Color.BLUE else Color.RED)
    ByteVector(image.bytes(PngWriter.MaxCompression))
  }

  private def load(bytes: ByteVector): ImmutableImage = ImmutableImage.loader().fromBytes(bytes.toArray)

  "Imaging.rotate" should {
    "leave the image untouched at 0 and at full turns" in {
      for {
        zero <- Imaging.rotate(halves, 0, ImageFormat.Png)
        full <- Imaging.rotate(halves, 720, ImageFormat.Png)
      } yield {
        List(zero, full).foreach { bytes =>
          val image = load(bytes)
          (image.width, image.height) shouldBe (40, 20)
          image.pixel(0, 0).toARGBInt shouldBe red
          image.pixel(39, 0).toARGBInt shouldBe blue
        }
        succeed
      }
    }

    "turn the right half to the bottom at 90 degrees" in {
      Imaging.rotate(halves, 90, ImageFormat.Png).map { bytes =>
        val image = load(bytes)
        (image.width, image.height) shouldBe (20, 40)
        image.pixel(0, 0).toARGBInt shouldBe red
        image.pixel(0, 39).toARGBInt shouldBe blue
      }
    }

    "swap the halves at 180 degrees" in {
      Imaging.rotate(halves, 180, ImageFormat.Png).map { bytes =>
        val image = load(bytes)
        (image.width, image.height) shouldBe (40, 20)
        image.pixel(0, 0).toARGBInt shouldBe blue
        image.pixel(39, 0).toARGBInt shouldBe red
      }
    }

    "turn the right half to the top at 270 degrees and treat -90 the same way" in {
      for {
        pos <- Imaging.rotate(halves, 270, ImageFormat.Png)
        neg <- Imaging.rotate(halves, -90, ImageFormat.Png)
      } yield {
        List(pos, neg).foreach { bytes =>
          val image = load(bytes)
          (image.width, image.height) shouldBe (20, 40)
          image.pixel(0, 0).toARGBInt shouldBe blue
          image.pixel(0, 39).toARGBInt shouldBe red
        }
        succeed
      }
    }

    "rotate by an arbitrary angle onto a larger canvas" in {
      Imaging.rotate(halves, 45, ImageFormat.Png).map { bytes =>
        val image = load(bytes)
        image.width should be > 40
        image.height should be > 20
      }
    }

    "write GIF output when asked" in {
      Imaging.rotate(halves, 90, ImageFormat.Gif).flatMap(Imaging.info).map { info =>
        info.map(_.format) shouldBe Some(ImageFormat.Gif)
        info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(20), Height(40)))
      }
    }
  }

  "Imaging.convert" should {
    "re-encode into the requested format" in {
      val gif = ByteVector(ImmutableImage.filled(8, 8, Color.GREEN).bytes(GifWriter.Default))
      Imaging.convert(gif, ImageFormat.Jpeg).flatMap(Imaging.info).map(_.map(_.format) shouldBe Some(ImageFormat.Jpeg))
    }
  }
}
