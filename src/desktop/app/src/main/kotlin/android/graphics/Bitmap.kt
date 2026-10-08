// Desktop shim for an Android framework API so ported OpenMinis code compiles unchanged on the JVM.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package android.graphics

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Minimal [android.graphics.Bitmap] backed by a java.awt [BufferedImage]. */
class Bitmap internal constructor(val image: BufferedImage) {
    enum class CompressFormat { JPEG, PNG, WEBP }

    val width: Int get() = image.width
    val height: Int get() = image.height

    fun recycle() {}

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        return when (format) {
            CompressFormat.PNG -> ImageIO.write(image, "png", stream)
            else -> {
                // JPEG has no alpha: flatten onto white.
                val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                val g = rgb.createGraphics()
                g.color = java.awt.Color.WHITE
                g.fillRect(0, 0, image.width, image.height)
                g.drawImage(image, 0, 0, null)
                g.dispose()
                val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = (quality.coerceIn(1, 100)) / 100f
                }
                ImageIO.createImageOutputStream(stream).use { ios ->
                    writer.output = ios
                    writer.write(null, IIOImage(rgb, null, null), params)
                }
                writer.dispose()
                true
            }
        }
    }

    companion object {
        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val type = if (src.image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            val out = BufferedImage(dstWidth, dstHeight, type)
            val g = out.createGraphics()
            if (filter) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src.image, 0, 0, dstWidth, dstHeight, null)
            g.dispose()
            return Bitmap(out)
        }
    }
}

object BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var outWidth: Int = -1
        @JvmField var outHeight: Int = -1
        @JvmField var outMimeType: String? = null
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? {
        return try {
            val img = ImageIO.read(data.inputStream(offset, length)) ?: return null
            opts?.outWidth = img.width
            opts?.outHeight = img.height
            if (opts?.inJustDecodeBounds == true) return null
            val bmp = Bitmap(img)
            val s = (opts?.inSampleSize ?: 1).coerceAtLeast(1)
            if (s > 1) Bitmap.createScaledBitmap(bmp, (img.width / s).coerceAtLeast(1), (img.height / s).coerceAtLeast(1), true) else bmp
        } catch (_: Throwable) {
            null
        }
    }
}
