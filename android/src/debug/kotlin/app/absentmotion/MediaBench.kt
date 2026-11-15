package app.absentmotion

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.media.FrameReader
import app.absentmotion.media.MediaReader
import app.absentmotion.media.VideoDecoder
import app.absentmotion.media.VideoSources
import app.absentmotion.media.Yuv
import app.absentmotion.media.hasTrack
import app.absentmotion.media.openDemuxer
import app.absentmotion.media.openGrabber
import app.absentmotion.media.ptsAt
import app.absentmotion.media.videoDeclaration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32

private const val SEEKS = 50
private const val SEQUENTIAL = 600
private const val TILES = 12

private class PeakMemory : AutoCloseable {
    @Volatile private var running = true
    @Volatile var peakKb = 0L
        private set
    val startKb = rssKb()
    private val thread = Thread {
        while (running) {
            peakKb = maxOf(peakKb, rssKb())
            Thread.sleep(10)
        }
    }.apply { isDaemon = true; start() }

    override fun close() { running = false; thread.join() }

    companion object {
        fun rssKb(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
    }
}

private inline fun step(result: JSONObject, name: String, run: (JSONObject) -> Unit) {
    val part = JSONObject()
    try {
        run(part)
        part.put("ok", true)
    } catch (e: Throwable) {
        part.put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}".take(300))
    }
    result.put(name, part)
}

private fun ms(since: Long) = (System.nanoTime() - since) / 1e6

private fun Yuv.signature(): Pair<String, String> {
    val crc = CRC32()
    val row = ByteArray(width)
    val grid = LongArray(16 * 9)
    val counts = IntArray(16 * 9)
    for (r in 0 until height) {
        val base = (top + r) * yRow + left * yPixel
        for (c in 0 until width) row[c] = y.get(base + c * yPixel)
        crc.update(row, 0, width)
        val gy = r * 9 / height
        for (c in 0 until width) {
            val cell = gy * 16 + c * 16 / width
            grid[cell] += (row[c].toInt() and 255).toLong()
            counts[cell]++
        }
    }
    val text = StringBuilder()
    for (i in grid.indices) text.append("%02x".format((grid[i] / maxOf(1, counts[i])).toInt()))
    return "%08x".format(crc.value) to text.toString()
}

private suspend fun benchFile(context: Context, file: File): JSONObject {
    val uri = Uri.fromFile(file).toString()
    val result = JSONObject().put("file", file.name).put("bytes", file.length())
    val memory = PeakMemory()
    var stamps: LongArray? = null
    var video = false
    var audio = false
    step(result, "open") { part ->
        val started = System.nanoTime()
        val demuxer = openDemuxer(context, Uri.parse(uri), "")
        try {
            val mimes = JSONArray()
            for (i in 0 until demuxer.trackCount) {
                val format = demuxer.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) video = true
                if (mime.startsWith("audio/")) audio = true
                mimes.put(mime)
            }
            part.put("ms", ms(started)).put("tracks", mimes).put("reader", demuxer.javaClass.simpleName)
        } finally { demuxer.release() }
    }
    if (!video) video = runCatching { openDemuxer(context, Uri.parse(uri), "video/").run { hasTrack("video/").also { release() } } }.getOrDefault(false)
    if (video) {
        step(result, "index") { part ->
            val started = System.nanoTime()
            val found = VideoSources.timestamps(context, uri)
            part.put("ms", ms(started)).put("frames", found.size).put("firstUs", found.first()).put("lastUs", found.last())
            stamps = found
        }
    }
    val frames = stamps
    if (frames != null) {
        val random = java.util.Random(file.name.hashCode().toLong())
        val picks = List(SEEKS) { random.nextInt(frames.size) }
        step(result, "seek") { part ->
            val opened = System.nanoTime()
            FrameReader(context, uri).use { reader ->
                part.put("openMs", ms(opened))
                val list = JSONArray()
                val times = ArrayList<Double>()
                var exact = 0
                for (k in picks) {
                    val started = System.nanoTime()
                    var signature: Pair<String, String>? = null
                    reader.read(listOf(frames[k])) { _, yuv -> signature = yuv.signature() }
                    val took = ms(started)
                    times += took
                    val (crc, grid) = signature ?: ("" to "")
                    if (signature != null) exact++
                    list.put(JSONObject().put("frame", k).put("pts", frames[k]).put("ms", took).put("crc", crc).put("grid", grid))
                }
                part.put("got", exact).put("medianMs", times.sorted()[times.size / 2]).put("maxMs", times.max()).put("seeks", list)
            }
        }
        step(result, "preview") { part ->
            val opened = System.nanoTime()
            VideoDecoder(context, uri).use { decoder ->
                part.put("openMs", ms(opened))
                val times = ArrayList<Double>()
                var shown = 0
                var right = 0
                for (k in picks) {
                    val started = System.nanoTime()
                    val image = decoder.frame(frames[k], true) { false }
                    times += ms(started)
                    if (image != null) {
                        shown++
                        if (kotlin.math.abs(image.timestamp / 1000 - frames[k]) <= 1000) right++
                    }
                }
                part.put("shown", shown).put("rightPts", right).put("medianMs", times.sorted()[times.size / 2]).put("maxMs", times.max())
            }
        }
        step(result, "sequential") { part ->
            FrameReader(context, uri).use { reader ->
                val wanted = frames.take(SEQUENTIAL)
                val started = System.nanoTime()
                val got = reader.read(wanted) { _, _ -> }
                val seconds = (System.nanoTime() - started) / 1e9
                part.put("frames", got).put("seconds", seconds).put("fps", got / seconds)
            }
        }
        step(result, "thumbnails") { part ->
            val started = System.nanoTime()
            val grabber = openGrabber(context, Uri.parse(uri))
            try {
                var made = 0
                for (i in 0 until TILES) {
                    val time = frames.ptsAt(frames.first() + (frames.last() - frames.first()) * i / TILES)
                    if (grabber.scaledFrame(time, MediaMetadataRetriever.OPTION_CLOSEST, 160, 96) != null) made++
                }
                part.put("ms", ms(started)).put("made", made).put("reader", grabber.javaClass.simpleName)
            } finally { grabber.release() }
        }
    }
    if (audio) {
        step(result, "audio") { part ->
            val started = System.nanoTime()
            PcmDecoder(context, uri).use { decoder ->
                var frameCount = 0L
                var rate = 0
                while (true) {
                    val chunk = decoder.next() ?: break
                    frameCount += chunk.frames
                    rate = chunk.rate
                }
                val seconds = (System.nanoTime() - started) / 1e9
                val length = if (rate > 0) frameCount.toDouble() / rate else 0.0
                part.put("seconds", seconds).put("audioSeconds", length).put("speed", if (seconds > 0) length / seconds else 0.0).put("rate", rate)
            }
        }
    }
    memory.close()
    if (video || !audio) result.getJSONObject("open").put("declared", videoDeclaration(context, Uri.parse(uri)) != null)
    result.put("rssStartMb", memory.startKb / 1024.0).put("rssPeakMb", memory.peakKb / 1024.0).put("rssGrowthMb", (memory.peakKb - memory.startKb) / 1024.0)
    return result
}

internal suspend fun runMediaBench(context: Context, folder: String) {
    val dir = File(context.getExternalFilesDir(null), folder).apply { mkdirs() }
    val out = File(context.getExternalFilesDir(null), "ab_bench.json")
    File(out.path + ".done").delete()
    val go = File(dir, ".go")
    val until = System.currentTimeMillis() + 600_000
    while (!go.exists() && System.currentTimeMillis() < until) kotlinx.coroutines.delay(500)
    go.delete()
    val files = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }
    val list = JSONArray()
    for (file in files) {
        Log.i("AMBench", "media ${file.name}")
        System.gc()
        list.put(benchFile(context, file))
        out.writeText(JSONObject().put("backend", if (MediaReader.ffamp(context)) "ffamp" else "android").put("package", context.packageName).put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT).put("files", list).toString(1))
    }
    File(out.path + ".done").writeText("done")
    Log.i("AMBench", "media bench done ${out.path}")
}
