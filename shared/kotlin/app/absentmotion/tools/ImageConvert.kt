package app.absentmotion.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import app.absentmotion.R
import app.absentmotion.export.ExportError
import app.absentmotion.export.GifWriter
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.txt
import app.absentmotion.media.GIF_MAX_FRAMES
import app.absentmotion.media.GIF_MAX_PIXELS
import app.absentmotion.media.GifDecoder
import app.absentmotion.media.GifError
import app.absentmotion.media.GifProblem
import app.absentmotion.media.decodePicture
import app.absentmotion.media.isGif
import app.absentmotion.media.summarizeGif
import app.absentmotion.platform.readAtMost
import app.absentmotion.platform.webpFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

enum class PictureFormat(val label: String, val ext: String, val mime: String) {
    Png("PNG", "png", "image/png"), Jpg("JPG", "jpg", "image/jpeg"), Webp("WebP", "webp", "image/webp"), Gif("GIF", "gif", "image/gif");

    companion object {
        fun of(head: ByteArray): PictureFormat? = when {
            isGif(head) -> Gif
            head.size >= 12 && head.ascii(0) == "RIFF" && head.ascii(8) == "WEBP" -> Webp
            head.size >= 4 && head[0] == 0x89.toByte() && head.ascii(1, 3) == "PNG" -> Png
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> Jpg
            else -> null
        }
    }
}

private fun ByteArray.ascii(at: Int, count: Int = 4) = String(this, at, count, Charsets.ISO_8859_1)

internal class WebpChunk(val tag: String, val data: ByteArray)

private fun ByteArray.le(at: Int, bytes: Int): Int = (0 until bytes).fold(0) { v, i -> v or ((this[at + i].toInt() and 255) shl (8 * i)) }

internal fun webpChunks(bytes: ByteArray, from: Int = 12, end: Int = if (bytes.size >= 8) minOf(bytes.size.toLong(), 8L + (bytes.le(4, 4).toLong() and 0xFFFFFFFFL)).toInt() else 0): List<WebpChunk> {
    val out = ArrayList<WebpChunk>()
    var at = from
    while (at + 8 <= end) {
        val size = bytes.le(at + 4, 4).toLong() and 0xFFFFFFFFL
        if (at + 8 + size > end) break
        out += WebpChunk(bytes.ascii(at), bytes.copyOfRange(at + 8, at + 8 + size.toInt()))
        at += 8 + size.toInt() + (size.toInt() and 1)
    }
    return out
}

private fun OutputStream.le(value: Int, bytes: Int) { for (i in 0 until bytes) write(value ushr (8 * i) and 255) }

private fun OutputStream.chunk(tag: String, data: ByteArray) {
    write(tag.toByteArray(Charsets.ISO_8859_1))
    le(data.size, 4)
    write(data)
    if (data.size and 1 == 1) write(0)
}

private fun header(width: Int, height: Int, flags: Int) = ByteArrayOutputStream(10).apply { le(flags, 4); le(width - 1, 3); le(height - 1, 3) }.toByteArray()

private val FrameTags = setOf("ALPH", "VP8 ", "VP8L")

internal fun stillWebp(chunks: List<WebpChunk>, width: Int, height: Int): ByteArray {
    val body = chunks.filter { it.tag in FrameTags }
    val lossy = body.any { it.tag == "VP8 " }
    val alpha = lossy && body.any { it.tag == "ALPH" }
    val parts = ByteArrayOutputStream().apply {
        if (alpha) chunk("VP8X", header(width, height, 0x10))
        body.filter { alpha || it.tag != "ALPH" }.forEach { chunk(it.tag, it.data) }
    }.toByteArray()
    return ByteArrayOutputStream(parts.size + 12).apply { write("RIFF".toByteArray()); le(parts.size + 4, 4); write("WEBP".toByteArray()); write(parts) }.toByteArray()
}

internal fun readAnimatedWebp(bytes: ByteArray, decode: (ByteArray) -> IntArray?, frame: (canvas: IntArray, width: Int, height: Int, durationMs: Int) -> Unit): Int {
    val chunks = webpChunks(bytes)
    val head = chunks.firstOrNull { it.tag == "VP8X" && it.data.size >= 10 } ?: throw GifError(GifProblem.Damaged)
    val width = 1 + head.data.le(4, 3)
    val height = 1 + head.data.le(7, 3)
    if (width.toLong() * height > GIF_MAX_PIXELS) throw GifError(GifProblem.TooLarge)
    val canvas = IntArray(width * height)
    var count = 0
    for (part in chunks) {
        if (part.tag != "ANMF" || part.data.size < 16) continue
        if (++count > GIF_MAX_FRAMES) throw GifError(GifProblem.TooLarge)
        val d = part.data
        val x = 2 * d.le(0, 3)
        val y = 2 * d.le(3, 3)
        val w = 1 + d.le(6, 3)
        val h = 1 + d.le(9, 3)
        val flags = d[15].toInt()
        val pixels = decode(stillWebp(webpChunks(d, 16, d.size), w, h))?.takeIf { it.size >= w * h } ?: throw GifError(GifProblem.Damaged)
        for (row in 0 until h) {
            val cy = y + row
            if (cy >= height) break
            for (col in 0 until w) {
                val cx = x + col
                if (cx >= width) break
                val i = cy * width + cx
                canvas[i] = if (flags and 2 != 0) pixels[row * w + col] else over(pixels[row * w + col], canvas[i])
            }
        }
        frame(canvas, width, height, d.le(12, 3))
        if (flags and 1 != 0) for (row in y until minOf(height, y + h)) canvas.fill(0, row * width + minOf(x, width), row * width + minOf(width, x + w))
    }
    if (count == 0) throw GifError(GifProblem.Damaged)
    return count
}

internal fun over(src: Int, dst: Int): Int {
    val sa = src ushr 24
    if (sa == 255) return src
    if (sa == 0) return dst
    val da = (dst ushr 24) * (255 - sa) / 255
    val a = sa + da
    fun mix(shift: Int) = (((src shr shift and 255) * sa + (dst shr shift and 255) * da) / a) shl shift
    return (a shl 24) or mix(16) or mix(8) or mix(0)
}

internal class AnimatedWebpWriter(private val file: RandomAccessFile, private val width: Int, private val height: Int) {
    init {
        file.setLength(0)
        file.write(ByteArrayOutputStream().apply {
            write("RIFF".toByteArray()); le(0, 4); write("WEBP".toByteArray())
            chunk("VP8X", header(width, height, 0x12))
            chunk("ANIM", ByteArray(6))
        }.toByteArray())
    }

    fun frame(still: ByteArray, durationMs: Int) {
        val inner = ByteArrayOutputStream(still.size + 32).apply {
            le(0, 6); le(width - 1, 3); le(height - 1, 3); le(durationMs.coerceIn(0, 0xFFFFFF), 3); write(2)
            webpChunks(still).filter { it.tag in FrameTags }.ifEmpty { throw GifError(GifProblem.Damaged) }.forEach { chunk(it.tag, it.data) }
        }.toByteArray()
        file.write(ByteArrayOutputStream(inner.size + 9).apply { chunk("ANMF", inner) }.toByteArray())
    }

    fun finish() {
        val size = file.length() - 8
        file.seek(4)
        file.write(ByteArray(4) { (size ushr (8 * it) and 255).toInt().toByte() })
    }
}

internal fun argbToRgba(argb: IntArray, opaque: Boolean): ByteArray {
    val out = ByteArray(argb.size * 4)
    for ((i, c) in argb.withIndex()) {
        val a = c ushr 24
        fun ch(shift: Int) = if (opaque) ((c shr shift and 255) * a + 255 * (255 - a) + 127) / 255 else c shr shift and 255
        out[4 * i] = ch(16).toByte(); out[4 * i + 1] = ch(8).toByte(); out[4 * i + 2] = ch(0).toByte(); out[4 * i + 3] = if (opaque) -1 else a.toByte()
    }
    return out
}

private fun whiteBacked(argb: IntArray): IntArray = argbToRgba(argb, true).let { rgba ->
    IntArray(argb.size) { i -> (255 shl 24) or ((rgba[4 * i].toInt() and 255) shl 16) or ((rgba[4 * i + 1].toInt() and 255) shl 8) or (rgba[4 * i + 2].toInt() and 255) }
}

private fun Bitmap.argb(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

private fun encodeWebp(argb: IntArray, width: Int, height: Int, lossless: Boolean): ByteArray {
    val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    try {
        return ByteArrayOutputStream().also { exportCheck(bitmap.compress(webpFormat(lossless), if (lossless) 100 else WebpQuality, it)) { txt(R.string.compress_unreadable) } }.toByteArray()
    } finally { bitmap.recycle() }
}

private const val WebpQuality = 90
private const val JpgQuality = 92
private const val MaxAnimationBytes = 64 shl 20

internal fun convertPictureFile(
    open: () -> InputStream, still: () -> Bitmap?, turn: Int, to: PictureFormat, out: File, check: () -> Unit = {}, progress: (Float) -> Unit = {},
): Int {
    val head = open().use { it.readAtMost(16) }
    val from = PictureFormat.of(head)
    val lossless = from == PictureFormat.Png || from == PictureFormat.Gif
    try {
        if (to == PictureFormat.Gif || to == PictureFormat.Webp) {
            if (from == PictureFormat.Gif) {
                val summary = summarizeGif(check, open)
                if (summary.frames > 1) return animation(out, to, summary.width, summary.height, lossless, summary.frames, check, progress) { emit ->
                    open().use { stream -> GifDecoder(stream).read { canvas, delay -> emit(canvas, delay) } }
                }
            }
            if (from == PictureFormat.Webp) {
                val bytes = open().use { it.readAtMost(MaxAnimationBytes + 1) }
                if (bytes.size > MaxAnimationBytes) throw GifError(GifProblem.TooLarge)
                val chunks = webpChunks(bytes)
                val canvas = chunks.firstOrNull { it.tag == "VP8X" && it.data.size >= 10 }?.data
                if (canvas != null && chunks.any { it.tag == "ANMF" }) {
                    return animation(out, to, 1 + canvas.le(4, 3), 1 + canvas.le(7, 3), false, chunks.count { it.tag == "ANMF" }, check, progress) { emit ->
                        readAnimatedWebp(bytes, { data -> BitmapFactory.decodeByteArray(data, 0, data.size)?.let { b -> b.argb().also { b.recycle() } } }) { canvas, _, _, duration -> emit(canvas, duration) }
                    }
                }
            }
        }
        var picture = still() ?: throw ExportError(txt(R.string.compress_unreadable))
        try {
            if (turn != 0) picture = Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, Matrix().apply { postRotate(turn.toFloat()) }, true).also { if (it !== picture) picture.recycle() }
            if (to == PictureFormat.Gif && picture.width.toLong() * picture.height > GIF_MAX_PIXELS) {
                val scale = kotlin.math.sqrt(GIF_MAX_PIXELS.toDouble() / (picture.width.toLong() * picture.height))
                picture = Bitmap.createScaledBitmap(picture, (picture.width * scale).toInt().coerceAtLeast(1), (picture.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== picture) picture.recycle() }
            }
            check()
            out.outputStream().buffered().use { stream ->
                when (to) {
                    PictureFormat.Png -> exportCheck(picture.compress(Bitmap.CompressFormat.PNG, 100, stream)) { txt(R.string.compress_unreadable) }
                    PictureFormat.Jpg -> Bitmap.createBitmap(whiteBacked(picture.argb()), picture.width, picture.height, Bitmap.Config.ARGB_8888).let { flat ->
                        try { exportCheck(flat.compress(Bitmap.CompressFormat.JPEG, JpgQuality, stream)) { txt(R.string.compress_unreadable) } } finally { flat.recycle() }
                    }
                    PictureFormat.Webp -> exportCheck(picture.compress(webpFormat(lossless), if (lossless) 100 else WebpQuality, stream)) { txt(R.string.compress_unreadable) }
                    PictureFormat.Gif -> GifWriter(stream, picture.width, picture.height, loop = false).apply { frame(argbToRgba(picture.argb(), true), 0); finish() }
                }
            }
            progress(1f)
            return 1
        } finally { picture.recycle() }
    } catch (e: GifError) {
        throw ExportError(txt(if (e.problem == GifProblem.TooLarge) R.string.tools_convert_too_large else R.string.compress_unreadable), e)
    }
}

private fun animation(
    out: File, to: PictureFormat, width: Int, height: Int, lossless: Boolean, frames: Int, check: () -> Unit, progress: (Float) -> Unit,
    read: ((IntArray, Int) -> Unit) -> Unit,
): Int {
    var count = 0
    fun step() { check(); progress(++count / frames.coerceAtLeast(1).toFloat()) }
    if (to == PictureFormat.Gif) out.outputStream().buffered().use { stream ->
        val writer = GifWriter(stream, width, height)
        var end = 0L
        read { canvas, delay -> end += delay; writer.frame(argbToRgba(canvas, true), end); step() }
        writer.finish()
    } else RandomAccessFile(out, "rw").use { file ->
        val writer = AnimatedWebpWriter(file, width, height)
        read { canvas, delay -> writer.frame(encodeWebp(canvas, width, height, lossless), delay); step() }
        writer.finish()
    }
    return count
}

suspend fun convertPicture(context: Context, picked: PickedMedia, to: PictureFormat, progress: (Float) -> Unit): Compressed = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val out = File(compressFolder(context).apply { mkdirs() }, outputName(picked, to.ext, ""))
    val job = currentCoroutineContext().job
    var finished = false
    try {
        val frames = convertPictureFile(
            { resolver.openInputStream(picked.uri) ?: throw ExportError(txt(R.string.compress_unreadable)) },
            { decodePicture(resolver, picked.uri, memorySide()) }, picked.rotation, to, out, { job.ensureActive() },
        ) { fraction -> progress(fraction) }
        finished = true
        Compressed(out, to.mime, false, frames)
    } finally {
        if (!finished) out.delete()
    }
}
