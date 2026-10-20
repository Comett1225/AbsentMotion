package app.absentmotion.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToLong

internal object Ebml {
    fun id(out: ByteArrayOutputStream, id: Int) {
        val bytes = (32 - Integer.numberOfLeadingZeros(id) + 7) / 8
        for (i in bytes - 1 downTo 0) out.write(id ushr (8 * i))
    }

    fun size(out: ByteArrayOutputStream, size: Long, length: Int = sizeLength(size)) {
        val value = size or (1L shl (7 * length))
        for (i in length - 1 downTo 0) out.write((value ushr (8 * i)).toInt())
    }

    fun sizeLength(size: Long): Int {
        var length = 1
        while (size >= (1L shl (7 * length)) - 1) length++
        return length
    }

    fun element(out: ByteArrayOutputStream, id: Int, body: ByteArray) {
        id(out, id)
        size(out, body.size.toLong())
        out.write(body)
    }

    fun uint(out: ByteArrayOutputStream, id: Int, value: Long) {
        var bytes = 1
        while (bytes < 8 && value ushr (8 * bytes) != 0L) bytes++
        element(out, id, ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() })
    }

    fun float(out: ByteArrayOutputStream, id: Int, value: Double) =
        element(out, id, ByteBuffer.allocate(8).putDouble(value).array())

    fun string(out: ByteArrayOutputStream, id: Int, value: String) = element(out, id, value.toByteArray())

    fun master(out: ByteArrayOutputStream, id: Int, children: ByteArrayOutputStream.() -> Unit) =
        element(out, id, ByteArrayOutputStream().apply(children).toByteArray())

    fun bytes(build: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(build).toByteArray()
}

internal class MatroskaWriter(private val out: FileChannel, private val docType: String = "matroska") {
    class Track(
        val video: Boolean,
        val codecId: String,
        val codecPrivate: ByteArray? = null,
        val width: Int = 0,
        val height: Int = 0,
        val frameDurationNs: Long = 0,
        val sampleRate: Double = 0.0,
        val channels: Int = 0,
        val codecDelayNs: Long = 0,
        val seekPreRollNs: Long = 0,
    )

    private class Cue(val timeMs: Long, val track: Int, val clusterPosition: Long)

    private var tracks: List<Track> = emptyList()
    private var segmentStart = 0L
    private var seekSpace = 0L
    private var durationAt = 0L
    private var infoPosition = 0L
    private var tracksPosition = 0L
    private val cues = ArrayList<Cue>()
    private var cluster: ByteArrayOutputStream? = null
    private var clusterMs = 0L
    private val lastUs = LongArray(16) { Long.MIN_VALUE }
    private val stepUs = LongArray(16)
    private var endUs = 0L

    private fun position() = out.position()

    private fun put(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) out.write(buffer)
    }

    private fun putAt(position: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        var at = position
        while (buffer.hasRemaining()) at += out.write(buffer, at)
    }

    fun start(tracks: List<Track>) {
        this.tracks = tracks
        put(Ebml.bytes {
            Ebml.master(this, 0x1A45DFA3) {
                Ebml.uint(this, 0x4286, 1); Ebml.uint(this, 0x42F7, 1); Ebml.uint(this, 0x42F2, 4); Ebml.uint(this, 0x42F3, 8)
                Ebml.string(this, 0x4282, docType); Ebml.uint(this, 0x4287, 4); Ebml.uint(this, 0x4285, 2)
            }
            Ebml.id(this, 0x18538067)
            Ebml.size(this, (1L shl 56) - 1, 8)
        })
        segmentStart = position()
        seekSpace = segmentStart
        put(void(SEEK_SPACE))
        infoPosition = position() - segmentStart
        val info = Ebml.bytes {
            Ebml.uint(this, 0x2AD7B1, 1_000_000)
            Ebml.string(this, 0x4D80, "AbsentMotion")
            Ebml.string(this, 0x5741, "AbsentMotion")
        }
        val head = Ebml.bytes { Ebml.id(this, 0x1549A966); Ebml.size(this, info.size + 11L) }
        put(head + info)
        durationAt = position() + 3
        put(Ebml.bytes { Ebml.float(this, 0x4489, 0.0) })
        tracksPosition = position() - segmentStart
        put(Ebml.bytes {
            Ebml.master(this, 0x1654AE6B) {
                tracks.forEachIndexed { i, t ->
                    Ebml.master(this, 0xAE) {
                        Ebml.uint(this, 0xD7, i + 1L)
                        Ebml.uint(this, 0x73C5, i + 1L)
                        Ebml.uint(this, 0x83, if (t.video) 1 else 2)
                        Ebml.uint(this, 0x9C, 0)
                        Ebml.string(this, 0x22B59C, "und")
                        Ebml.string(this, 0x86, t.codecId)
                        t.codecPrivate?.let { Ebml.element(this, 0x63A2, it) }
                        if (t.frameDurationNs > 0) Ebml.uint(this, 0x23E383, t.frameDurationNs)
                        if (t.codecDelayNs > 0) Ebml.uint(this, 0x56AA, t.codecDelayNs)
                        if (t.seekPreRollNs > 0) Ebml.uint(this, 0x56BB, t.seekPreRollNs)
                        if (t.video) Ebml.master(this, 0xE0) {
                            Ebml.uint(this, 0xB0, t.width.toLong())
                            Ebml.uint(this, 0xBA, t.height.toLong())
                            Ebml.master(this, 0x55B0) {
                                Ebml.uint(this, 0x55B1, 1); Ebml.uint(this, 0x55B9, 1); Ebml.uint(this, 0x55BA, 1); Ebml.uint(this, 0x55BB, 1)
                            }
                        } else Ebml.master(this, 0xE1) {
                            Ebml.float(this, 0xB5, t.sampleRate)
                            Ebml.uint(this, 0x9F, t.channels.toLong())
                        }
                    }
                }
            }
        })
    }

    fun write(track: Int, data: ByteArray, timeUs: Long, key: Boolean) {
        val t = tracks[track]
        val ms = Math.floorDiv(timeUs + 500, 1000)
        val video = tracks.any { it.video }
        val open = cluster
        val split = open == null || ms - clusterMs !in -32000..32000 || open.size() > CLUSTER_BYTES ||
            (key && t.video && ms != clusterMs) || (!video && ms - clusterMs >= 2000)
        if (split) {
            flushCluster()
            clusterMs = ms
            cluster = ByteArrayOutputStream().also { Ebml.uint(it, 0xE7, maxOf(ms, 0)) }
            if (t.video && key || !video) cues += Cue(maxOf(ms, 0), track + 1, position() - segmentStart)
            if (ms < 0) clusterMs = 0
        }
        val relative = (ms - clusterMs).toInt()
        val block = cluster!!
        Ebml.id(block, 0xA3)
        Ebml.size(block, data.size + 4L)
        block.write(0x80 or (track + 1))
        block.write(relative shr 8)
        block.write(relative)
        block.write(if (key) 0x80 else 0)
        block.write(data)
        if (lastUs[track] != Long.MIN_VALUE && timeUs > lastUs[track]) stepUs[track] = timeUs - lastUs[track]
        lastUs[track] = maxOf(lastUs[track], timeUs)
        val step = if (t.frameDurationNs > 0) t.frameDurationNs / 1000 else stepUs[track]
        endUs = maxOf(endUs, timeUs + step)
    }

    private fun flushCluster() {
        val body = cluster?.toByteArray() ?: return
        cluster = null
        put(Ebml.bytes { Ebml.element(this, 0x1F43B675, body) })
    }

    fun finish() {
        flushCluster()
        val cuesPosition = position() - segmentStart
        if (cues.isNotEmpty()) put(Ebml.bytes {
            Ebml.master(this, 0x1C53BB6B) {
                cues.forEach { cue ->
                    Ebml.master(this, 0xBB) {
                        Ebml.uint(this, 0xB3, cue.timeMs)
                        Ebml.master(this, 0xB7) {
                            Ebml.uint(this, 0xF7, cue.track.toLong())
                            Ebml.uint(this, 0xF1, cue.clusterPosition)
                        }
                    }
                }
            }
        })
        val end = position()
        putAt(durationAt, ByteBuffer.allocate(8).putDouble(endUs / 1000.0).array())
        val seek = Ebml.bytes {
            Ebml.master(this, 0x114D9B74) {
                listOf(0x1549A966 to infoPosition, 0x1654AE6B to tracksPosition, 0x1C53BB6B to cuesPosition)
                    .filter { it.first != 0x1C53BB6B || cues.isNotEmpty() }
                    .forEach { (id, at) ->
                        Ebml.master(this, 0x4DBB) {
                            Ebml.element(this, 0x53AB, Ebml.bytes { Ebml.id(this, id) })
                            Ebml.element(this, 0x53AC, ByteBuffer.allocate(8).putLong(at).array())
                        }
                    }
            }
        }
        putAt(seekSpace, seek + void(SEEK_SPACE - seek.size))
        putAt(segmentStart - 8, Ebml.bytes { Ebml.size(this, end - segmentStart, 8) })
        out.position(end)
    }

    private fun void(total: Int): ByteArray {
        val body = total - 1 - Ebml.sizeLength(total.toLong())
        return Ebml.bytes { Ebml.id(this, 0xEC); Ebml.size(this, body.toLong(), total - 1 - body); write(ByteArray(body)) }
    }

    companion object {
        private const val SEEK_SPACE = 100
        private const val CLUSTER_BYTES = 8 shl 20
    }
}

internal object NalUnits {
    fun split(data: ByteArray, from: Int = 0, to: Int = data.size): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = from
        while (i + 2 < to) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i to i + 3
                i += 3
            } else i++
        }
        if (starts.isEmpty()) return if (to > from) listOf(data.copyOfRange(from, to)) else emptyList()
        return starts.indices.map { k ->
            var end = if (k + 1 < starts.size) starts[k + 1].first else to
            while (end > starts[k].second && data[end - 1].toInt() == 0 && k + 1 < starts.size) end--
            data.copyOfRange(starts[k].second, end)
        }.filter { it.isNotEmpty() }
    }

    fun lengthPrefixed(annexB: ByteArray): ByteArray = Ebml.bytes {
        for (nal in split(annexB)) {
            write(nal.size ushr 24); write(nal.size ushr 16); write(nal.size ushr 8); write(nal.size)
            write(nal)
        }
    }

    fun avcC(nals: List<ByteArray>): ByteArray {
        val sps = nals.first { it[0].toInt() and 0x1F == 7 }
        val pps = nals.filter { it[0].toInt() and 0x1F == 8 }
        return Ebml.bytes {
            write(1); write(sps[1].toInt()); write(sps[2].toInt()); write(sps[3].toInt()); write(0xFF); write(0xE1)
            write(sps.size ushr 8); write(sps.size); write(sps)
            write(pps.size)
            pps.forEach { write(it.size ushr 8); write(it.size); write(it) }
            if (sps[1].toInt() and 0xFF in listOf(100, 110, 122, 144)) { write(0xFD); write(0xF8); write(0xF8); write(0) }
        }
    }

    private fun rbsp(nal: ByteArray, limit: Int): ByteArray = Ebml.bytes {
        var zeros = 0
        for (b in nal) {
            if (size() >= limit) break
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
    }

    fun hvcC(nals: List<ByteArray>): ByteArray {
        fun type(nal: ByteArray) = (nal[0].toInt() ushr 1) and 0x3F
        val sps = rbsp(nals.first { type(it) == 33 }, 16)
        val layers = ((sps[2].toInt() ushr 1) and 7) + 1
        val nested = sps[2].toInt() and 1
        val arrays = listOf(32, 33, 34).map { kind -> kind to nals.filter { type(it) == kind } }.filter { it.second.isNotEmpty() }
        return Ebml.bytes {
            write(1)
            write(sps, 3, 12)
            write(0xF0); write(0); write(0xFC); write(0xFD); write(0xF8); write(0xF8); write(0); write(0)
            write((layers shl 3) or (nested shl 2) or 3)
            write(arrays.size)
            for ((kind, list) in arrays) {
                write(0x80 or kind)
                write(list.size ushr 8); write(list.size)
                list.forEach { write(it.size ushr 8); write(it.size); write(it) }
            }
        }
    }

    fun withoutTemporalDelimiter(obus: ByteArray): ByteArray =
        if (obus.size >= 2 && obus[0].toInt() == 0x12 && obus[1].toInt() == 0) obus.copyOfRange(2, obus.size) else obus
}

internal class OpusConfig(val head: ByteArray, val delayNs: Long, val preRollNs: Long)

internal fun opusConfig(csd0: ByteArray, csd1: ByteArray?, csd2: ByteArray?): OpusConfig {
    fun nanos(bytes: ByteArray?) = bytes?.takeIf { it.size >= 8 }?.let { ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).long } ?: 0L
    if (csd0.size < 16 || String(csd0, 0, 8, Charsets.US_ASCII) != "AOPUSHDR") return OpusConfig(csd0, nanos(csd1), nanos(csd2).takeIf { it > 0 } ?: 80_000_000L)
    val parts = HashMap<String, ByteArray>()
    val buffer = ByteBuffer.wrap(csd0).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    while (buffer.remaining() >= 16) {
        val marker = ByteArray(8).also { buffer.get(it) }
        val length = buffer.long.toInt().coerceIn(0, buffer.remaining())
        parts[String(marker, Charsets.US_ASCII)] = ByteArray(length).also { buffer.get(it) }
    }
    return OpusConfig(parts["AOPUSHDR"] ?: csd0, nanos(parts["AOPUSDLY"] ?: csd1), nanos(parts["AOPUSPRL"] ?: csd2).takeIf { it > 0 } ?: 80_000_000L)
}

internal fun frameDurationNs(fps: Int): Long = if (fps > 0) (1e9 / fps).roundToLong() else 0
