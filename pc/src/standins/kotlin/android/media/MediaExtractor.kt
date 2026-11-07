package android.media

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.desk.DeskStream
import java.io.File
import java.nio.ByteBuffer

open class MediaExtractor {
    private var file: File? = null
    private var probe: DeskProbe? = null
    private val selected = sortedSetOf<Int>()
    private var samples: List<Sample>? = null
    private var at = 0

    private class Sample(val track: Int, val timeUs: Long, val size: Int, val sync: Boolean)

    fun setDataSource(context: Context?, uri: Uri, headers: Map<String, String>?) = open(uri.toFile() ?: throw java.io.IOException("Cannot open $uri"))
    fun setDataSource(path: String) = open(Uri.parse(path).toFile() ?: File(path))
    fun setDataSource(path: String, headers: Map<String, String>?) = setDataSource(path)
    fun setDataSource(fd: java.io.FileDescriptor): Unit = throw java.io.IOException("File descriptors are not supported on this computer")
    fun setDataSource(fd: java.io.FileDescriptor, offset: Long, length: Long): Unit = setDataSource(fd)

    private fun open(source: File) {
        val found = DeskProbe.of(source)
        if (found.streams.isEmpty()) throw java.io.IOException("No tracks in ${source.name}")
        file = source
        probe = found
    }

    val trackCount: Int get() = probe?.streams?.size ?: 0

    fun getTrackFormat(index: Int): MediaFormat = format(probe!!.streams[index])

    fun selectTrack(index: Int) { require(index in 0 until trackCount); selected += index; samples = null }
    fun unselectTrack(index: Int) { selected -= index; samples = null }

    val sampleTime: Long get() = current()?.timeUs ?: -1L
    val sampleSize: Long get() = current()?.size?.toLong() ?: -1L
    val sampleFlags: Int get() = current()?.let { if (it.sync) SAMPLE_FLAG_SYNC else 0 } ?: -1
    val sampleTrackIndex: Int get() = current()?.track ?: -1
    val cachedDuration: Long get() = -1L

    fun advance(): Boolean { if (current() == null) return false; at++; return current() != null }

    fun seekTo(timeUs: Long, mode: Int) {
        val list = index()
        val syncs = list.indices.filter { list[it].sync || !list[it].trackIsVideo() }
        val before = syncs.lastOrNull { list[it].timeUs <= timeUs }
        val after = syncs.firstOrNull { list[it].timeUs >= timeUs }
        at = when (mode) {
            SEEK_TO_NEXT_SYNC -> after ?: list.size
            SEEK_TO_CLOSEST_SYNC -> listOfNotNull(before, after).minByOrNull { kotlin.math.abs(list[it].timeUs - timeUs) } ?: 0
            else -> before ?: syncs.firstOrNull() ?: 0
        }
    }

    fun readSampleData(buffer: ByteBuffer, offset: Int): Int = throw UnsupportedOperationException("Compressed samples are not available on this computer")

    fun release() { probe = null; samples = null; selected.clear() }

    private fun Sample.trackIsVideo() = probe?.streams?.getOrNull(track)?.type == "video"

    private fun current(): Sample? = index().getOrNull(at)

    private fun index(): List<Sample> {
        samples?.let { return it }
        val source = file ?: return emptyList()
        val streams = probe?.streams ?: return emptyList()
        val list = ArrayList<Sample>()
        for (track in selected) {
            val stream = streams[track]
            val process = ProcessBuilder(DeskMedia.ffprobe, "-v", "error", "-select_streams", "${stream.index}",
                "-show_entries", "packet=pts_time,dts_time,size,pos,flags", "-of", "compact=p=0", source.path)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.inputStream.bufferedReader().forEachLine { line ->
                val kv = line.split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
                val time = (kv["pts_time"]?.toDoubleOrNull() ?: kv["dts_time"]?.toDoubleOrNull() ?: return@forEachLine)
                list += Sample(track, Math.round(time * 1_000_000) - stream.startUs, kv["size"]?.toIntOrNull() ?: 0, kv["flags"]?.startsWith("K") == true)
            }
            process.waitFor()
        }
        if (selected.size > 1) list.sortBy { it.timeUs }
        at = 0
        return list.also { samples = it }
    }

    companion object {
        const val SAMPLE_FLAG_SYNC = 1
        const val SAMPLE_FLAG_ENCRYPTED = 2
        const val SAMPLE_FLAG_PARTIAL_FRAME = 4
        const val SEEK_TO_PREVIOUS_SYNC = 0
        const val SEEK_TO_NEXT_SYNC = 1
        const val SEEK_TO_CLOSEST_SYNC = 2

        fun format(s: DeskStream): MediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, s.mime)
            if (s.durationUs > 0) setLong(MediaFormat.KEY_DURATION, s.durationUs)
            if (s.bitRate > 0) setInteger(MediaFormat.KEY_BIT_RATE, s.bitRate)
            if (s.language.isNotEmpty()) setString("language", s.language)
            if (s.type == "audio") {
                setInteger(MediaFormat.KEY_SAMPLE_RATE, s.sampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, s.channels)
            }
            if (s.type == "video") {
                setInteger(MediaFormat.KEY_WIDTH, s.width)
                setInteger(MediaFormat.KEY_HEIGHT, s.height)
                if (s.fps > 0) { if (s.fps == Math.rint(s.fps)) setInteger(MediaFormat.KEY_FRAME_RATE, s.fps.toInt()) else setFloat(MediaFormat.KEY_FRAME_RATE, s.fps.toFloat()) }
                if (s.rotation != 0) setInteger(MediaFormat.KEY_ROTATION, s.rotation)
                when (s.transfer) {
                    "smpte2084" -> MediaFormat.COLOR_TRANSFER_ST2084
                    "arib-std-b67" -> MediaFormat.COLOR_TRANSFER_HLG
                    "bt709", "bt601", "smpte170m", "bt470bg" -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    else -> null
                }?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
                when (s.range) { "pc" -> MediaFormat.COLOR_RANGE_FULL; "tv" -> MediaFormat.COLOR_RANGE_LIMITED; else -> null }?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
                when (s.primaries) { "bt709" -> MediaFormat.COLOR_STANDARD_BT709; "bt470bg" -> MediaFormat.COLOR_STANDARD_BT601_PAL; "smpte170m" -> MediaFormat.COLOR_STANDARD_BT601_NTSC; else -> null }
                    ?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
            }
        }
    }
}
