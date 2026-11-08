package app.absentmotion.desk

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DeskStream(
    val index: Int, val type: String, val codec: String, val profile: String, val sampleRate: Int, val channels: Int,
    val width: Int, val height: Int, val fps: Double, val durationUs: Long, val rotation: Int, val transfer: String,
    val range: String, val primaries: String, val bitRate: Int, val language: String, val startUs: Long = 0,
) {
    val mime: String get() = MIMES[codec] ?: if (codec.startsWith("pcm_")) "audio/raw" else "$type/$codec"

    private companion object {
        val MIMES = mapOf(
            "aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "opus" to "audio/opus", "vorbis" to "audio/vorbis", "flac" to "audio/flac",
            "alac" to "audio/alac", "ac3" to "audio/ac3", "eac3" to "audio/eac3", "amr_nb" to "audio/3gpp", "amr_wb" to "audio/amr-wb",
            "h264" to "video/avc", "hevc" to "video/hevc", "vp8" to "video/x-vnd.on2.vp8", "vp9" to "video/x-vnd.on2.vp9", "av1" to "video/av01",
            "mpeg4" to "video/mp4v-es", "h263" to "video/3gpp", "mpeg2video" to "video/mpeg2", "mjpeg" to "video/mjpeg",
        )
    }
}

class DeskProbe(val streams: List<DeskStream>, val durationUs: Long, val tags: Map<String, String>, val startUs: Long = 0) {
    val audio: DeskStream? get() = streams.firstOrNull { it.type == "audio" }
    val video: DeskStream? get() = streams.firstOrNull { it.type == "video" }

    companion object {
        private val cache = ConcurrentHashMap<String, Pair<String, DeskProbe>>()

        fun of(file: File): DeskProbe {
            val stamp = "${file.length()}:${file.lastModified()}"
            cache[file.path]?.takeIf { it.first == stamp }?.let { return it.second }
            if (!file.isFile) throw java.io.FileNotFoundException(file.path)
            val process = ProcessBuilder(DeskMedia.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", file.path)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return parse(JSONObject(text.ifBlank { "{}" })).also { cache[file.path] = stamp to it }
        }

        fun parse(json: JSONObject): DeskProbe {
            val format = json.optJSONObject("format")
            val total = seconds(format?.optString("duration"))
            val list = json.optJSONArray("streams")
            val streams = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }.filter { s ->
                s.optString("codec_type") in setOf("audio", "video", "subtitle") && s.optJSONObject("disposition")?.optInt("attached_pic") != 1
            }.map { s ->
                val tags = s.optJSONObject("tags")
                val side = s.optJSONArray("side_data_list")
                val turn = tags?.optString("rotate")?.toIntOrNull()
                    ?: (0 until (side?.length() ?: 0)).firstNotNullOfOrNull { side!!.getJSONObject(it).opt("rotation") as? Number }?.let { -it.toInt() }
                    ?: 0
                DeskStream(
                    s.optInt("index"), s.optString("codec_type"), s.optString("codec_name"), s.optString("profile"),
                    s.optString("sample_rate").toIntOrNull() ?: 0, s.optInt("channels"), s.optInt("width"), s.optInt("height"),
                    rate(s.optString("avg_frame_rate")).takeIf { it > 0 } ?: rate(s.optString("r_frame_rate")),
                    seconds(s.optString("duration")).takeIf { it > 0 } ?: total, Math.floorMod(turn, 360),
                    s.optString("color_transfer"), s.optString("color_range"), s.optString("color_primaries"),
                    s.optString("bit_rate").toIntOrNull() ?: 0, tags?.optString("language").orEmpty(),
                    s.optString("start_time").toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Math.round(it * 1_000_000) } ?: 0L,
                )
            }
            val tags = format?.optJSONObject("tags")?.let { t -> t.keySet().associate { it.lowercase() to t.optString(it) } }.orEmpty()
            val start = format?.optString("start_time")?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Math.round(it * 1_000_000) }
            return DeskProbe(streams, total, tags, start ?: streams.minOfOrNull { it.startUs } ?: 0L)
        }

        private fun seconds(text: String?): Long = text?.toDoubleOrNull()?.let { (it * 1_000_000).toLong() } ?: -1L

        private fun rate(text: String): Double {
            val n = text.substringBefore('/').toDoubleOrNull() ?: return 0.0
            val d = text.substringAfter('/', "1").toDoubleOrNull() ?: return 0.0
            return if (d > 0) n / d else 0.0
        }
    }
}
