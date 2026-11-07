package android.graphics

import java.awt.image.BufferedImage
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

class Bitmap(val image: BufferedImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height
    var isPremultiplied = true
    val isMutable: Boolean get() = true
    fun hasAlpha(): Boolean = image.colorModel.hasAlpha()
    fun setHasAlpha(on: Boolean) {}
    var density: Int = 160
    var isRecycled = false; private set
    val config: Config get() = Config.ARGB_8888
    val byteCount: Int get() = width * height * 4
    val allocationByteCount: Int get() = byteCount
    fun recycle() { isRecycled = true }
    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)
    fun setPixel(x: Int, y: Int, c: Int) = image.setRGB(x, y, c)
    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) { image.getRGB(x, y, w, h, out, offset, stride) }
    fun setPixels(src: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) { image.setRGB(x, y, w, h, src, offset, stride) }
    fun copyPixelsFromBuffer(buffer: ByteBuffer) {
        val px = IntArray(width * height)
        for (i in px.indices) {
            val r = buffer.get().toInt() and 255; val g = buffer.get().toInt() and 255; val b = buffer.get().toInt() and 255; val a = buffer.get().toInt() and 255
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        image.setRGB(0, 0, width, height, px, 0, width)
    }
    fun copyPixelsToBuffer(buffer: ByteBuffer) {
        val px = image.getRGB(0, 0, width, height, null, 0, width)
        for (c in px) { buffer.put((c shr 16).toByte()); buffer.put((c shr 8).toByte()); buffer.put(c.toByte()); buffer.put((c ushr 24).toByte()) }
    }
    fun compress(format: CompressFormat, quality: Int, out: OutputStream): Boolean {
        if (format == CompressFormat.PNG) return ImageIO.write(image, "png", out)
        val jpeg = format == CompressFormat.JPEG
        val q = quality.coerceIn(0, 100)
        val source = if (jpeg) BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { it.graphics.drawImage(image, 0, 0, null) } else image
        val px = source.getRGB(0, 0, width, height, null, 0, width)
        val rgba = ByteArray(px.size * 4)
        for ((i, c) in px.withIndex()) { rgba[4 * i] = (c shr 16).toByte(); rgba[4 * i + 1] = (c shr 8).toByte(); rgba[4 * i + 2] = c.toByte(); rgba[4 * i + 3] = (c ushr 24).toByte() }
        val data = if (jpeg) {
            val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
            org.jetbrains.skia.Image.makeRaster(info, rgba, width * 4).use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, q)?.use { d -> d.bytes } }
        } else app.absentmotion.pc.engine.DesktopNative.encodeWebp(rgba, width, height, q, format == CompressFormat.WEBP_LOSSLESS || (format == CompressFormat.WEBP && q == 100))
        out.write(data ?: return false)
        return true
    }
    fun copy(config: Config, mutable: Boolean): Bitmap = Bitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { it.graphics.drawImage(image, 0, 0, null) })
    fun eraseColor(c: Int) { val g = image.createGraphics(); g.composite = java.awt.AlphaComposite.Src; g.color = java.awt.Color(c, true); g.fillRect(0, 0, width, height); g.dispose() }

    enum class Config { ARGB_8888, RGB_565, ALPHA_8, HARDWARE, RGBA_F16 }
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    companion object {
        @JvmStatic fun createBitmap(w: Int, h: Int, config: Config = Config.ARGB_8888) = Bitmap(BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB))
        @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int) = Bitmap(src.image.getSubimage(x, y, w, h))
        @JvmStatic fun createBitmap(colors: IntArray, w: Int, h: Int, config: Config) = createBitmap(w, h).also { it.image.setRGB(0, 0, w, h, colors, 0, w) }
        @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?, filter: Boolean): Bitmap {
            val part = src.image.getSubimage(x, y, w, h)
            if (m == null || m.isIdentity()) return Bitmap(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(part, 0, 0, null); dispose() } })
            val pts = floatArrayOf(0f, 0f, w.toFloat(), 0f, 0f, h.toFloat(), w.toFloat(), h.toFloat())
            m.mapPoints(pts)
            val minX = minOf(pts[0], pts[2], pts[4], pts[6]); val minY = minOf(pts[1], pts[3], pts[5], pts[7])
            val outW = Math.round(maxOf(pts[0], pts[2], pts[4], pts[6]) - minX).coerceAtLeast(1); val outH = Math.round(maxOf(pts[1], pts[3], pts[5], pts[7]) - minY).coerceAtLeast(1)
            val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            if (filter) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val v = m.v
            g.transform(java.awt.geom.AffineTransform(v[0].toDouble(), v[3].toDouble(), v[1].toDouble(), v[4].toDouble(), (v[2] - minX).toDouble(), (v[5] - minY).toDouble()))
            g.drawImage(part, 0, 0, null); g.dispose()
            return Bitmap(out)
        }
        @JvmStatic fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val out = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            if (filter) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src.image, 0, 0, out.width, out.height, null); g.dispose()
            return Bitmap(out)
        }
    }
}

object BitmapFactory {
    class Options { var inBitmap: Bitmap? = null; var inScaled = true; var inDensity = 0; var inTargetDensity = 0; var inPremultiplied = true; var inJustDecodeBounds = false; var inSampleSize = 1; var outWidth = 0; var outHeight = 0; var outMimeType: String? = null; var inPreferredConfig: Bitmap.Config? = null; var inMutable = false }

    private fun argb(img: BufferedImage): BufferedImage = if (img.type == BufferedImage.TYPE_INT_ARGB) img else BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }

    @JvmStatic fun decodeStream(input: InputStream?, pad: Rect? = null, options: Options? = null): Bitmap? {
        input ?: return null
        val bytes = input.readBytes()
        val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
        options?.apply { outWidth = img.width; outHeight = img.height }
        if (options?.inJustDecodeBounds == true) return null
        val s = options?.inSampleSize?.coerceAtLeast(1) ?: 1
        val full = Bitmap(argb(img))
        return if (s > 1) Bitmap.createScaledBitmap(full, img.width / s, img.height / s, true) else full
    }
    @JvmStatic fun decodeStream(input: InputStream?): Bitmap? = decodeStream(input, null, null)
    @JvmStatic fun decodeFile(path: String, options: Options? = null): Bitmap? = java.io.File(path).takeIf { it.isFile }?.inputStream()?.use { decodeStream(it, null, options) }
    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int, options: Options? = null): Bitmap? = decodeStream(data.inputStream(offset, length), null, options)
}

class Matrix {
    val v = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    fun reset() { setValues(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) }
    fun setValues(a: FloatArray) { a.copyInto(v, 0, 0, 9) }
    fun getValues(out: FloatArray) { v.copyInto(out) }
    fun set(m: Matrix) = setValues(m.v)
    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }
    fun preConcat(m: Matrix): Boolean { setValues(mul(v, m.v)); return true }
    fun postConcat(m: Matrix): Boolean { setValues(mul(m.v, v)); return true }
    fun setConcat(a: Matrix, b: Matrix): Boolean { setValues(mul(a.v, b.v)); return true }
    fun postTranslate(x: Float, y: Float) = postConcat(Matrix().apply { v[2] = x; v[5] = y })
    fun preTranslate(x: Float, y: Float) = preConcat(Matrix().apply { v[2] = x; v[5] = y })
    fun setTranslate(x: Float, y: Float) { reset(); v[2] = x; v[5] = y }
    fun postScale(x: Float, y: Float) = postConcat(Matrix().apply { v[0] = x; v[4] = y })
    fun postScale(x: Float, y: Float, px: Float, py: Float) = postConcat(Matrix().apply { v[0] = x; v[4] = y; v[2] = px - x * px; v[5] = py - y * py })
    fun preScale(x: Float, y: Float) = preConcat(Matrix().apply { v[0] = x; v[4] = y })
    fun preSkew(x: Float, y: Float) = preConcat(Matrix().apply { v[1] = x; v[3] = y })
    fun postSkew(x: Float, y: Float) = postConcat(Matrix().apply { v[1] = x; v[3] = y })
    fun setScale(x: Float, y: Float) { reset(); v[0] = x; v[4] = y }
    private fun rot(d: Float, px: Float, py: Float) = Matrix().apply { val r = Math.toRadians(d.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat(); setValues(floatArrayOf(c, -s, px - c * px + s * py, s, c, py - s * px - c * py, 0f, 0f, 1f)) }
    fun postRotate(d: Float) = postConcat(rot(d, 0f, 0f))
    fun postRotate(d: Float, px: Float, py: Float) = postConcat(rot(d, px, py))
    fun preRotate(d: Float) = preConcat(rot(d, 0f, 0f))
    fun setRotate(d: Float) = setValues(rot(d, 0f, 0f).v)
    fun mapPoints(pts: FloatArray) = mapPoints(pts, pts)
    fun mapPoints(dst: FloatArray, src: FloatArray) {
        var i = 0
        while (i + 1 < src.size) {
            val x = src[i]; val y = src[i + 1]; val w = v[6] * x + v[7] * y + v[8]
            dst[i] = (v[0] * x + v[1] * y + v[2]) / w; dst[i + 1] = (v[3] * x + v[4] * y + v[5]) / w; i += 2
        }
    }
    fun mapVectors(pts: FloatArray) { var i = 0; while (i + 1 < pts.size) { val x = pts[i]; val y = pts[i + 1]; pts[i] = v[0] * x + v[1] * y; pts[i + 1] = v[3] * x + v[4] * y; i += 2 } }
    fun invert(out: Matrix): Boolean {
        val a = v; val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (det == 0f) return false
        val d = 1f / det
        out.setValues(floatArrayOf(
            (a[4] * a[8] - a[5] * a[7]) * d, (a[2] * a[7] - a[1] * a[8]) * d, (a[1] * a[5] - a[2] * a[4]) * d,
            (a[5] * a[6] - a[3] * a[8]) * d, (a[0] * a[8] - a[2] * a[6]) * d, (a[2] * a[3] - a[0] * a[5]) * d,
            (a[3] * a[7] - a[4] * a[6]) * d, (a[1] * a[6] - a[0] * a[7]) * d, (a[0] * a[4] - a[1] * a[3]) * d))
        return true
    }
    fun isIdentity() = v.contentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    companion object { const val MSCALE_X = 0; const val MSKEW_X = 1; const val MTRANS_X = 2; const val MSKEW_Y = 3; const val MSCALE_Y = 4; const val MTRANS_Y = 5; const val MPERSP_0 = 6; const val MPERSP_1 = 7; const val MPERSP_2 = 8 }
}

data class Rect(@JvmField var left: Int = 0, @JvmField var top: Int = 0, @JvmField var right: Int = 0, @JvmField var bottom: Int = 0) {
    fun width() = right - left
    fun height() = bottom - top
}

data class RectF(@JvmField var left: Float = 0f, @JvmField var top: Float = 0f, @JvmField var right: Float = 0f, @JvmField var bottom: Float = 0f) {
    fun width() = right - left
    fun height() = bottom - top
}

object Color {
    const val BLACK = -0x1000000; const val WHITE = -1; const val TRANSPARENT = 0; const val RED = -0x10000; const val GREEN = -0xff0100; const val BLUE = -0xffff01
    @JvmStatic fun alpha(c: Int) = c ushr 24
    @JvmStatic fun red(c: Int) = (c shr 16) and 255
    @JvmStatic fun green(c: Int) = (c shr 8) and 255
    @JvmStatic fun blue(c: Int) = c and 255
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    @JvmStatic fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
    @JvmStatic fun parseColor(s: String): Int {
        val h = s.removePrefix("#")
        return when (h.length) { 6 -> (0xff000000 or h.toLong(16)).toInt(); 8 -> h.toLong(16).toInt(); else -> throw IllegalArgumentException("Unknown color") }
    }
    @JvmStatic fun colorToHSV(c: Int, hsv: FloatArray) { java.awt.Color.RGBtoHSB(red(c), green(c), blue(c), hsv); hsv[0] *= 360f }
    @JvmStatic fun HSVToColor(hsv: FloatArray): Int = HSVToColor(255, hsv)
    @JvmStatic fun HSVToColor(a: Int, hsv: FloatArray): Int = (java.awt.Color.HSBtoRGB(hsv[0] / 360f, hsv[1], hsv[2]) and 0xffffff) or (a shl 24)
}

class Paint

class Canvas(private val bitmap: Bitmap) {
    fun drawColor(color: Int) {
        val g = bitmap.image.createGraphics()
        g.composite = java.awt.AlphaComposite.Src
        g.color = java.awt.Color(color, true)
        g.fillRect(0, 0, bitmap.width, bitmap.height)
        g.dispose()
    }

    fun drawBitmap(source: Bitmap, left: Float, top: Float, paint: Paint?) {
        val g = bitmap.image.createGraphics()
        g.drawImage(source.image, left.toInt(), top.toInt(), null)
        g.dispose()
    }
}
