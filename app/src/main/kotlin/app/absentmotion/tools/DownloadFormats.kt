package app.absentmotion.tools

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class VideoCodec { Avc, Hevc, Av1, Vp9, Vp8, Unknown, None }

enum class AudioCodec { Aac, Opus, Vorbis, Mp3, Unknown, None }

enum class SaveMode { Both, VideoOnly, AudioOnly }

data class RemoteFormat(
    val id: String,
    val ext: String,
    val video: VideoCodec,
    val audio: AudioCodec,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val kbps: Float = 0f,
    val bytes: Long = 0L,
    val protocol: String = "https",
    val original: Boolean = false,
    val watermarked: Boolean = false,
    val order: Int = 0,
    val channels: Int = 0,
    val drc: Boolean = false,
) {
    val plainSound get() = channels <= 2 && !drc
    val hasVideo get() = video != VideoCodec.None
    val hasAudio get() = audio != AudioCodec.None
    val side get() = if (width > 0 && height > 0) minOf(width, height) else height
}

data class RemoteItem(val title: String, val durationSeconds: Double, val thumbnails: List<String>, val formats: List<RemoteFormat>, val pictures: List<List<String>>)

data class LinkInfo(val title: String, val thumbnails: List<String>, val site: String, val items: List<RemoteItem>) {
    val pictures get() = items.isNotEmpty() && items.all { it.formats.isEmpty() && it.pictures.isNotEmpty() }
    val first get() = items.firstOrNull()
}

data class Plan(val video: RemoteFormat?, val audio: RemoteFormat?, val webm: Boolean = false, val reencodeAudio: Boolean = false) {
    val bytes get() = (video?.bytes ?: 0L) + (audio?.takeIf { it !== video }?.bytes ?: 0L)
}

data class Quality(val side: Int, val fps: Int, val kbps: Int, val plan: Plan)

private fun videoCodec(raw: String?): VideoCodec {
    val text = raw?.lowercase()?.trim() ?: return VideoCodec.Unknown
    return when {
        text == "none" -> VideoCodec.None
        text.startsWith("avc") || text.startsWith("h264") -> VideoCodec.Avc
        text.startsWith("hev") || text.startsWith("hvc") || text.startsWith("h265") || text.startsWith("hevc") || text.startsWith("bytevc1") -> VideoCodec.Hevc
        text.startsWith("av01") || text == "av1" -> VideoCodec.Av1
        text.startsWith("vp09") || text.startsWith("vp9") -> VideoCodec.Vp9
        text.startsWith("vp8") -> VideoCodec.Vp8
        else -> VideoCodec.Unknown
    }
}

private fun audioCodec(raw: String?): AudioCodec {
    val text = raw?.lowercase()?.trim() ?: return AudioCodec.Unknown
    return when {
        text == "none" -> AudioCodec.None
        text == "mp4a.40.34" || text == "mp4a.6b" || text.startsWith("mp3") -> AudioCodec.Mp3
        text.startsWith("mp4a") || text.startsWith("aac") -> AudioCodec.Aac
        text.startsWith("opus") -> AudioCodec.Opus
        text.startsWith("vorbis") -> AudioCodec.Vorbis
        else -> AudioCodec.Unknown
    }
}

private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
private fun JSONObject.number(name: String): Double = if (isNull(name)) 0.0 else optDouble(name, 0.0).takeIf { !it.isNaN() } ?: 0.0

private fun parseFormat(json: JSONObject, order: Int): RemoteFormat {
    val ext = json.text("ext")?.lowercase().orEmpty()
    var video = videoCodec(json.text("v"))
    var audio = audioCodec(json.text("a"))
    if (ext in AudioExtensions && video == VideoCodec.Unknown) video = VideoCodec.None
    if (ext == "m4a" && audio == AudioCodec.Unknown) audio = AudioCodec.Aac
    if (ext == "mp3" && audio == AudioCodec.Unknown) audio = AudioCodec.Mp3
    val note = json.text("note")?.lowercase().orEmpty()
    return RemoteFormat(
        id = json.text("id") ?: "0", ext = ext, video = video, audio = audio,
        width = json.number("w").toInt(), height = json.number("h").toInt(), fps = json.number("fps").toFloat(),
        kbps = json.number("abr").takeIf { it > 0 && video == VideoCodec.None }?.toFloat() ?: json.number("tbr").toFloat(),
        bytes = json.number("size").toLong(), protocol = json.text("proto") ?: "https", original = "original" in note || "default" in note,
        watermarked = "watermark" in note, order = order, channels = json.number("ch").toInt(),
        drc = "drc" in note || json.text("id").orEmpty().lowercase().endsWith("-drc"),
    )
}

private val AudioExtensions = setOf("m4a", "mp3", "opus", "ogg", "oga", "aac", "weba", "wav", "flac")

private fun JSONArray?.texts(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

fun parseLinkInfo(text: String): LinkInfo {
    val json = JSONObject(text)
    val items = json.optJSONArray("items") ?: JSONArray()
    return LinkInfo(
        title = json.text("title").orEmpty(),
        thumbnails = json.optJSONArray("thumbnails").texts(),
        site = json.text("site").orEmpty(),
        items = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val formats = item.optJSONArray("formats") ?: JSONArray()
            val pictures = item.optJSONArray("pictures") ?: JSONArray()
            RemoteItem(
                title = item.text("title").orEmpty(), durationSeconds = item.number("duration"), thumbnails = item.optJSONArray("thumbnails").texts(),
                formats = (0 until formats.length()).map { parseFormat(formats.getJSONObject(it), it) },
                pictures = (0 until pictures.length()).map { pictures.optJSONArray(it).texts() }.filter { it.isNotEmpty() },
            )
        },
    )
}

private val Containers = setOf("mp4", "m4v", "mov", "webm", "3gp", "mkv", "ts")

private fun streamable(format: RemoteFormat) = format.ext in Containers || "m3u8" in format.protocol || "dash" in format.protocol

private fun videoRank(codec: VideoCodec) = when (codec) {
    VideoCodec.Avc -> 5
    VideoCodec.Hevc -> 4
    VideoCodec.Av1 -> 3
    VideoCodec.Vp9 -> 2
    else -> 0
}

private fun webm(format: RemoteFormat, av1: Boolean): Boolean? = when (format.video) {
    VideoCodec.Avc, VideoCodec.Hevc -> false
    VideoCodec.Av1 -> if (av1) false else null
    VideoCodec.Vp9, VideoCodec.Vp8 -> true
    VideoCodec.Unknown -> format.ext == "webm"
    else -> null
}

private val WebmSound = setOf(AudioCodec.Opus, AudioCodec.Vorbis, AudioCodec.Unknown)
private val Mp4Sound = setOf(AudioCodec.Aac, AudioCodec.Unknown)

private fun direct(format: RemoteFormat) = if ("m3u8" in format.protocol || "dash" in format.protocol) 0 else 1

private fun bestAudio(formats: List<RemoteFormat>, codecs: Set<AudioCodec>): RemoteFormat? =
    formats.filter { !it.hasVideo && it.audio in codecs }
        .maxWithOrNull(compareBy<RemoteFormat>({ it.original }, { it.plainSound }, { direct(it) }, { it.kbps }, { it.bytes }, { it.order }))

private fun progressive(format: RemoteFormat) = format.hasVideo && format.hasAudio && streamable(format)

private fun videoPlans(formats: List<RemoteFormat>, withSound: Boolean, av1: Boolean): List<Plan> {
    val aac = bestAudio(formats, setOf(AudioCodec.Aac))
    val opus = bestAudio(formats, setOf(AudioCodec.Opus, AudioCodec.Vorbis))
    val plans = ArrayList<Plan>()
    for (format in formats) {
        if (!format.hasVideo || !streamable(format)) continue
        val webm = webm(format, av1) ?: continue
        if (progressive(format)) {
            if (format.audio in (if (webm) WebmSound else Mp4Sound)) plans += Plan(format, null, webm)
            continue
        }
        if (format.hasAudio) continue
        val sound = if (webm) opus else aac
        if (!withSound) plans += Plan(format, null, webm) else if (sound != null) plans += Plan(format, sound, webm)
    }
    return plans
}

fun qualities(item: RemoteItem, mode: SaveMode, av1Decoder: Boolean = true): List<Quality> {
    val formats = item.formats.filterNot { it.watermarked }
    if (formats.isEmpty()) return emptyList()
    if (mode == SaveMode.AudioOnly) {
        val separate = formats.filter { !it.hasVideo && it.hasAudio && it.plainSound }
        val kept = separate.filter { it.audio == AudioCodec.Aac }.ifEmpty { separate }.let { sound -> sound.filter { direct(it) == 1 }.ifEmpty { sound } }
        if (kept.isEmpty()) {
            val source = formats.filter(::progressive).maxWithOrNull(compareBy({ direct(it) }, { it.audio == AudioCodec.Aac }, { it.kbps }, { it.bytes }, { it.order })) ?: return emptyList()
            return listOf(Quality(0, 0, 0, Plan(null, source, reencodeAudio = source.audio != AudioCodec.Aac && source.audio != AudioCodec.Unknown)))
        }
        return kept.map { Plan(null, it, reencodeAudio = it.audio != AudioCodec.Aac) }.groupBy { (it.audio!!.kbps / 8).toInt() }.map { (_, group) ->
            group.maxWith(compareBy<Plan>({ it.audio!!.original }, { it.audio!!.kbps }, { it.audio!!.order }))
        }.sortedByDescending { it.audio!!.kbps }.map { Quality(0, 0, it.audio!!.kbps.roundToInt(), it) }
    }
    val plans = videoPlans(formats, mode == SaveMode.Both && formats.any { it.hasAudio }, av1Decoder)
    val sized = plans.filter { it.video!!.side > 0 }.ifEmpty { plans }
    return sized.groupBy { rung(it.video!!.side) to fpsClass(it.video.fps) }.map { (key, group) ->
        val chosen = group.maxWith(compareBy<Plan>(
            { direct(it.video!!) },
            { it.audio == null || mode == SaveMode.VideoOnly },
            { it.video!!.side },
            { videoRank(it.video!!.video) },
            { it.video!!.kbps },
            { it.bytes },
            { it.video!!.order },
        ))
        Quality(key.first, key.second, 0, chosen)
    }.sortedWith(compareByDescending<Quality> { it.side }.thenByDescending { it.fps })
}

private fun fpsClass(fps: Float) = if (fps >= 48f) fps.toInt().coerceAtMost(240) else 0

private val Ladder = intArrayOf(4320, 2160, 1440, 1080, 720, 540, 480, 360, 240, 144)

fun rung(side: Int): Int = if (side <= 0) 0 else Ladder.firstOrNull { side >= it * 0.92 } ?: 144

private val Bitrates = intArrayOf(32, 48, 64, 96, 128, 160, 192, 256, 320)

fun usualKbps(kbps: Int): Int = Bitrates.minByOrNull { kotlin.math.abs(it - kbps) }?.takeIf { kotlin.math.abs(it - kbps) <= it / 10 } ?: kbps

fun defaultQuality(options: List<Quality>, mode: SaveMode): Int {
    if (options.isEmpty()) return -1
    if (mode == SaveMode.AudioOnly) return 0
    val fitting = options.indexOfFirst { it.side in 1..1080 }
    return if (fitting >= 0) fitting else 0
}

fun picturesOnly(item: RemoteItem) = item.formats.isEmpty() && item.pictures.isNotEmpty()
