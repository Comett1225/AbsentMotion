package app.absentmotion.media

import java.io.EOFException
import java.io.InputStream

enum class GifProblem { NotGif, TooLarge, Damaged }

class GifError(val problem: GifProblem) : Exception(problem.name)

class GifSummary(val width: Int, val height: Int, val delaysMs: IntArray, val transparent: Boolean) {
    val frames: Int get() = delaysMs.size
    val durationMs: Long get() = delaysMs.sumOf { it.toLong() }
}

const val GIF_MAX_PIXELS = 2048 * 2048
const val GIF_MAX_FRAMES = 3000
const val GIF_MAX_WORK = 600_000_000L

fun gifDelayMs(centiseconds: Int): Int = if (centiseconds <= 1) 100 else centiseconds * 10

fun gifStartsUs(delaysMs: IntArray): LongArray {
    var at = 0L
    return LongArray(delaysMs.size) { i -> (at * 1000).also { at += delaysMs[i] } }
}

fun isGif(head: ByteArray): Boolean = head.size >= 6 && String(head, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" }

class GifDecoder(stream: InputStream) {
    private val input = stream.buffered(65536)
    val width: Int
    val height: Int
    private val global: IntArray?
    private val background: Int
    private var canvas = IntArray(0)
    private var saved: IntArray? = null
    private val prefix = ShortArray(4096)
    private val suffix = ByteArray(4096)
    private val stack = ByteArray(4097)
    private val block = ByteArray(256)

    init {
        val head = ByteArray(6)
        if (fill(head, 6) != 6 || !isGif(head)) throw GifError(GifProblem.NotGif)
        width = short()
        height = short()
        val packed = byte()
        background = byte()
        byte()
        if (width <= 0 || height <= 0) throw GifError(GifProblem.Damaged)
        if (width.toLong() * height > GIF_MAX_PIXELS) throw GifError(GifProblem.TooLarge)
        global = if (packed and 0x80 != 0) palette(2 shl (packed and 7)) else null
    }

    fun read(frame: (IntArray, Int) -> Unit): Int {
        canvas = IntArray(width * height)
        var count = 0
        var delay = 0
        var dispose = 0
        var transparentIndex = -1
        var pixels = ByteArray(0)
        while (true) {
            when (input.read()) {
                0x21 -> {
                    val label = byte()
                    if (label == 0xF9) {
                        val size = byte()
                        if (size < 4) throw GifError(GifProblem.Damaged)
                        val packed = byte()
                        delay = short()
                        val index = byte()
                        skip(size - 4)
                        skipBlocks()
                        dispose = packed shr 2 and 7
                        transparentIndex = if (packed and 1 != 0) index else -1
                    } else skipBlocks()
                }
                0x2C -> {
                    val left = short()
                    val top = short()
                    val w = short()
                    val h = short()
                    val packed = byte()
                    val local = if (packed and 0x80 != 0) palette(2 shl (packed and 7)) else null
                    val colours = local ?: global ?: IntArray(256) { 0xFF000000.toInt() or (it * 0x010101) }
                    if (w.toLong() * h > GIF_MAX_PIXELS) throw GifError(GifProblem.TooLarge)
                    if (pixels.size < w * h) pixels = ByteArray(w * h)
                    lzw(pixels, w * h)
                    if (dispose == 3) saved = canvas.copyOf()
                    draw(pixels, colours, left, top, w, h, packed and 0x40 != 0, transparentIndex)
                    if (++count > GIF_MAX_FRAMES) throw GifError(GifProblem.TooLarge)
                    frame(canvas, gifDelayMs(delay))
                    when (dispose) {
                        2 -> clear(left, top, w, h)
                        3 -> saved?.let { canvas = it; saved = null }
                    }
                    delay = 0
                    dispose = 0
                    transparentIndex = -1
                }
                0x3B, -1 -> return if (count > 0) count else throw GifError(GifProblem.Damaged)
                0x00 -> Unit
                else -> if (count > 0) return count else throw GifError(GifProblem.Damaged)
            }
        }
    }

    private fun draw(pixels: ByteArray, colours: IntArray, left: Int, top: Int, w: Int, h: Int, interlaced: Boolean, transparentIndex: Int) {
        var row = 0
        var pass = 0
        var step = if (interlaced) 8 else 1
        for (line in 0 until h) {
            val y = top + row
            if (y in 0 until height) {
                val from = line * w
                val to = y * width
                for (x in 0 until w) {
                    val cx = left + x
                    if (cx !in 0 until width) continue
                    val index = pixels[from + x].toInt() and 255
                    if (index == transparentIndex || index >= colours.size) continue
                    canvas[to + cx] = colours[index]
                }
            }
            row += step
            while (interlaced && row >= h && pass < 3) {
                pass++
                row = intArrayOf(0, 4, 2, 1).getOrElse(pass) { h }
                step = intArrayOf(8, 8, 4, 2).getOrElse(pass) { 1 }
            }
        }
    }

    private fun clear(left: Int, top: Int, w: Int, h: Int) {
        for (y in maxOf(0, top) until minOf(height, top + h)) {
            canvas.fill(0, y * width + maxOf(0, left), y * width + minOf(width, left + w))
        }
    }

    private fun lzw(out: ByteArray, size: Int) {
        val minimum = byte()
        if (minimum !in 1..11) throw GifError(GifProblem.Damaged)
        val clear = 1 shl minimum
        val end = clear + 1
        var available = clear + 2
        var codeSize = minimum + 1
        var mask = (1 shl codeSize) - 1
        var old = -1
        var first = 0
        var bits = 0
        var datum = 0
        var left = 0
        var at = 0
        var top = 0
        var written = 0
        var ended = false
        for (code in 0 until clear) { prefix[code] = 0; suffix[code] = code.toByte() }
        loop@ while (written < size) {
            if (top == 0) {
                while (bits < codeSize) {
                    if (left == 0) {
                        left = input.read()
                        if (left <= 0) { ended = true; left = 0; break@loop }
                        if (fill(block, left) != left) break@loop
                        at = 0
                    }
                    datum = datum or ((block[at++].toInt() and 255) shl bits)
                    bits += 8
                    left--
                }
                if (bits < codeSize) break
                var code = datum and mask
                datum = datum ushr codeSize
                bits -= codeSize
                if (code == clear) {
                    codeSize = minimum + 1
                    mask = (1 shl codeSize) - 1
                    available = clear + 2
                    old = -1
                    continue
                }
                if (code == end) break
                if (old == -1) {
                    stack[top++] = suffix[code]
                    old = code
                    first = code
                    continue
                }
                val incoming = code
                if (code >= available) {
                    stack[top++] = first.toByte()
                    code = old
                }
                while (code >= clear) {
                    if (top >= stack.size || code >= 4096) throw GifError(GifProblem.Damaged)
                    stack[top++] = suffix[code]
                    code = prefix[code].toInt()
                }
                first = suffix[code].toInt() and 255
                stack[top++] = first.toByte()
                if (available < 4096) {
                    prefix[available] = old.toShort()
                    suffix[available] = first.toByte()
                    available++
                    if (available and mask == 0 && available < 4096) {
                        codeSize++
                        mask += available
                    }
                }
                old = incoming
            }
            top--
            out[written++] = stack[top]
        }
        if (written < size) out.fill(0, written, size)
        if (!ended) skipBlocks()
    }

    private fun palette(size: Int): IntArray {
        val raw = ByteArray(size * 3)
        if (fill(raw, raw.size) != raw.size) throw GifError(GifProblem.Damaged)
        return IntArray(size) { i ->
            0xFF000000.toInt() or ((raw[i * 3].toInt() and 255) shl 16) or ((raw[i * 3 + 1].toInt() and 255) shl 8) or (raw[i * 3 + 2].toInt() and 255)
        }
    }

    private fun fill(into: ByteArray, count: Int): Int {
        var done = 0
        while (done < count) {
            val n = input.read(into, done, count - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    private fun skipBlocks() {
        while (true) {
            val size = input.read()
            if (size <= 0) return
            skip(size)
        }
    }

    private fun skip(count: Int) {
        var left = count.toLong()
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) { if (input.read() < 0) return; left-- } else left -= skipped
        }
    }

    private fun byte(): Int = input.read().also { if (it < 0) throw GifError(GifProblem.Damaged) }

    private fun short(): Int = byte() or (byte() shl 8)
}

fun summarizeGif(check: () -> Unit = {}, open: () -> InputStream): GifSummary {
    val delays = ArrayList<Int>()
    var transparent = false
    var width = 0
    var height = 0
    var total = 0L
    try {
        open().use { stream ->
            val decoder = GifDecoder(stream)
            width = decoder.width
            height = decoder.height
            decoder.read { canvas, delay ->
                check()
                delays += delay
                total += delay
                if (delays.size.toLong() * width * height > GIF_MAX_WORK || total > GIF_MAX_MS) throw GifError(GifProblem.TooLarge)
                if (!transparent) transparent = canvas.any { it ushr 24 != 255 }
            }
        }
    } catch (e: GifError) { throw e } catch (_: EOFException) { if (delays.isEmpty()) throw GifError(GifProblem.Damaged) }
    return GifSummary(width, height, delays.toIntArray(), transparent)
}

const val GIF_MAX_MS = 10 * 60 * 1000L
