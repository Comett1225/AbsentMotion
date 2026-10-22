package app.absentmotion.export

import android.content.Context
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Scene
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

internal const val DefaultSetupMs = 300L

data class ExportRecord(
    val codec: Codec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: Int,
    val ms: Long,
    val interpolation: FrameInterpolation = FrameInterpolation.Off,
)

internal fun codecCost(codec: Codec): Double = when (codec) {
    Codec.H264, Codec.Apv -> 1.0
    Codec.Hevc -> 1.3
    Codec.Vp9 -> 2.0
    Codec.Av1 -> 2.5
}

internal fun estimateExportMs(
    history: List<ExportRecord>, probe: ProbeResult?, codec: Codec, width: Int, height: Int, fps: Int, frames: Int,
    interpolation: FrameInterpolation = FrameInterpolation.Off,
): Long? {
    val megapixels = width.toDouble() * height / 1e6
    val work = frames * megapixels
    val usable = history.filter { it.frames > 0 && it.width > 0 && it.height > 0 && it.ms > 0 }
    val pool = listOf<(ExportRecord) -> Boolean>(
        { it.codec == codec && it.interpolation == interpolation },
        { it.interpolation == interpolation },
        { true },
    ).map { test -> usable.filter(test) }.firstOrNull { it.isNotEmpty() }
    val overhead = probe?.setupMs?.takeIf { it > 0 } ?: DefaultSetupMs
    if (pool != null) {
        fun distance(r: ExportRecord) = abs(ln(r.width.toDouble() * r.height / 1e6 / megapixels)) + 0.5 * abs(ln(r.fps.toDouble() / fps))
        val close = pool.filter { distance(it) <= 0.9 }.sortedBy(::distance).take(5)
            .ifEmpty { if (probe == null) listOf(pool.minBy(::distance)) else emptyList() }
        if (close.isNotEmpty()) {
            val msPerMegapixel = close.sumOf { maxOf(it.ms - overhead, it.ms / 4) * codecCost(codec) / codecCost(it.codec) } /
                close.sumOf { it.frames * it.width.toDouble() * it.height / 1e6 }
            return (overhead + msPerMegapixel * work).roundToLong()
        }
    }
    probe ?: return null
    val msPerMegapixel = 1000.0 / (probe.pixelsPerSecond / 1e6) * codecCost(codec) / codecCost(probe.codec)
    return (probe.setupMs + msPerMegapixel * work).roundToLong()
}

internal fun encoderFloorMs(encoder: EncoderCaps?, width: Int, height: Int, fps: Int, frames: Int): Long? {
    val rated = encoder?.ratedFps(width, height)?.takeIf { it > 0 } ?: return null
    return if (!encoder.hardware || rated < fps) (frames * 1000.0 / rated).roundToLong() else null
}

internal fun sizeRatio(records: List<Pair<Long, Long>>): Double {
    val ratios = records.filter { it.first > 0 && it.second > 0 }.takeLast(5).map { it.second.toDouble() / it.first }.sorted()
    if (ratios.isEmpty()) return 1.0
    val middle = ratios.size / 2
    val median = if (ratios.size % 2 == 1) ratios[middle] else (ratios[middle - 1] + ratios[middle]) / 2
    return median.coerceIn(0.05, 4.0)
}

class ExportSizes(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("export_sizes", Context.MODE_PRIVATE)

    private fun records(key: String): List<Pair<Long, Long>> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
    }.getOrDefault(emptyList())

    fun ratio(key: String): Double = sizeRatio(records(key))

    @Synchronized fun record(key: String, estimated: Long, actual: Long) {
        if (estimated <= 0 || actual <= 0) return
        val kept = (records(key) + (estimated to actual)).takeLast(10)
        prefs.edit().putString(key, JSONArray().apply { kept.forEach { put(JSONArray().put(it.first).put(it.second)) } }.toString()).apply()
    }

    companion object {
        fun key(kind: ExportKind, s: ExportSettings): String? = when (kind) {
            ExportKind.Video -> "video.${s.codec.name}"
            ExportKind.Gif -> "gif"
            else -> null
        }
    }
}

fun timeLabel(ms: Long): UiText = when {
    ms < 10_000 -> txt(R.string.export_time_few_seconds)
    ms < 90_000 -> ((ms + 2_500) / 5_000 * 5).toInt().let { UiText.plural(R.plurals.export_time_seconds, it, it.toString()) }
    ms < 3_600_000 -> ((ms + 30_000) / 60_000).toInt().let { UiText.plural(R.plurals.export_time_minutes, it, it.toString()) }
    else -> {
        val hours = if (ms < 36_000_000) (ms / 360_000.0).roundToLong() / 10.0 else ((ms + 1_800_000) / 3_600_000).toDouble()
        val text = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(hours)
        UiText.plural(R.plurals.export_time_hours_count, kotlin.math.ceil(hours).toInt(), text)
    }
}

internal fun recordsToJson(records: List<ExportRecord>): JSONArray = JSONArray().apply {
    records.forEach {
        put(JSONObject().put("codec", it.codec.name).put("width", it.width).put("height", it.height).put("fps", it.fps)
            .put("frames", it.frames).put("ms", it.ms).put("interpolation", it.interpolation.name))
    }
}

internal fun recordsFromJson(array: JSONArray): List<ExportRecord> = (0 until array.length()).mapNotNull { i ->
    val o = array.optJSONObject(i) ?: return@mapNotNull null
    val codec = Codec.entries.firstOrNull { it.name == o.optString("codec") } ?: return@mapNotNull null
    ExportRecord(codec, o.optInt("width"), o.optInt("height"), o.optInt("fps"), o.optInt("frames"), o.optLong("ms"),
        FrameInterpolation.entries.firstOrNull { it.name == o.optString("interpolation") } ?: FrameInterpolation.Off)
}

class ExportTimes(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("export_history", Context.MODE_PRIVATE)

    fun history(): List<ExportRecord> =
        prefs.getString("records", null)?.let { runCatching { recordsFromJson(JSONArray(it)) }.getOrNull() }.orEmpty()

    @Synchronized fun record(record: ExportRecord) {
        val kept = (history() + record).takeLast(40)
        prefs.edit().putString("records", recordsToJson(kept).toString()).apply()
    }

    fun estimateMs(
        s: ExportSettings, scene: Scene, probe: ProbeResult? = DeviceCapabilities.of(app).report.value?.probe,
        thermal: ThermalLevel = ThermalLevel.Normal, encoders: DeviceReport? = null,
    ): Long? {
        val (w, h) = exportSize(scene, s.shortSide, even = true)
        val frames = frameCount(scene, s.fps)
        val history = history()
        val learned = estimateExportMs(history, probe, s.codec, w, h, s.fps, frames, s.interpolation)
        val proven = history.any { it.codec == s.codec && it.width == w && it.height == h && it.fps == s.fps && it.interpolation == s.interpolation }
        val floor = encoders?.takeIf { !proven }?.let { encoderFloorMs(it.encoderFor(s.codec, w, h), w, h, s.fps, frames) }
        return listOfNotNull(learned, floor).maxOrNull()?.let { it + pauseMs(thermal) * frames }
    }
}
