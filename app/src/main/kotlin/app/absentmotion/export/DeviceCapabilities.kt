package app.absentmotion.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SizePoint(val width: Int, val height: Int, val maxFps: Double)

data class EncoderCaps(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val widths: IntRange = 0..0,
    val heights: IntRange = 0..0,
    val widthAlign: Int = 1,
    val heightAlign: Int = 1,
    val sizes: List<SizePoint> = emptyList(),
    val bitrates: IntRange = 0..0,
    val profiles: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
    val maxChannels: Int = 0,
) {
    val video: Boolean get() = mime.startsWith("video/")

    private fun covering(width: Int, height: Int) = sizes.filter { it.width >= width && it.height >= height }

    fun fitsSize(width: Int, height: Int): Boolean =
        width in widths && height in heights && width % widthAlign == 0 && height % heightAlign == 0 && covering(width, height).isNotEmpty()

    fun fits(width: Int, height: Int, fps: Int): Boolean = fitsSize(width, height) &&
        covering(width, height).any { it.maxFps * it.width * it.height >= fps.toDouble() * width * height * 0.999 }
}

data class ProbeResult(val codec: Codec, val width: Int, val height: Int, val frames: Int, val ms: Long, val setupMs: Long = 0) {
    val pixelsPerSecond: Double get() = width.toDouble() * height * (frames - 1).coerceAtLeast(1) * 1000.0 / (ms - setupMs).coerceAtLeast(1)
}

sealed interface Support {
    data object Supported : Support
    data class SlowSoftwareOnly(val reason: String) : Support
    data class Unsupported(val reason: String, val suggestion: ExportSettings?) : Support
}

data class DeviceReport(val key: String, val encoders: List<EncoderCaps>, val probe: ProbeResult? = null) {
    fun encodersFor(mime: String): List<EncoderCaps> = encoders.filter { it.mime == mime }

    fun hardware(codec: Codec): Boolean = encodersFor(codec.mime).any { it.hardware }

    val videoCodecs: List<Codec> get() = Codec.entries.filter { encodersFor(it.mime).isNotEmpty() }

    fun check(s: ExportSettings, width: Int, height: Int, hasAudio: Boolean = true): Support {
        formatProblem(s, hasAudio)?.let { return Support.Unsupported(it, s.fixedFormat()) }
        val all = encodersFor(s.codec.mime)
        if (all.isEmpty()) {
            return Support.Unsupported("This phone has no ${s.codec.label} encoder.", alternative(s, width, height))
        }
        val sized = all.filter { it.fitsSize(width, height) }
        if (sized.isEmpty()) {
            return Support.Unsupported("${s.codec.label} cannot encode ${width}x$height on this phone.", alternative(s, width, height))
        }
        if (sized.none { it.hardware }) {
            return Support.SlowSoftwareOnly("This phone encodes ${s.codec.label} in software only, so the export will be slow.")
        }
        if (sized.none { it.hardware && it.fits(width, height, s.fps) }) {
            return Support.SlowSoftwareOnly("This phone's ${s.codec.label} encoder is rated below ${s.fps} fps at ${width}x$height, so the export will be slow.")
        }
        return Support.Supported
    }

    private fun alternative(s: ExportSettings, width: Int, height: Int): ExportSettings? {
        val short = minOf(width, height).coerceAtLeast(1)
        val codecs = (listOf(Codec.H264, Codec.Hevc) + Codec.entries).distinct()
        val sides = VideoResolutions.map { it.shortSide }.filter { it < s.shortSide }
        fun size(side: Int) = Pair(
            ((width.toLong() * side / short).toInt() + 1) and 1.inv(),
            ((height.toLong() * side / short).toInt() + 1) and 1.inv(),
        )
        fun fit(codec: Codec, side: Int, hardware: Boolean): Boolean {
            val (w, h) = if (side == s.shortSide) width to height else size(side)
            return encodersFor(codec.mime).any { (it.hardware || !hardware) && it.fitsSize(w, h) }
        }
        val order = codecs.filter { it != s.codec }.map { it to s.shortSide } +
            sides.flatMap { side -> (listOf(s.codec) + codecs).distinct().map { it to side } }
        for (hardware in listOf(true, false)) {
            order.firstOrNull { (codec, side) -> fit(codec, side, hardware) }?.let { (codec, side) -> return s.copy(codec = codec, shortSide = side).fixedFormat() }
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().put("key", key)
        .put("encoders", JSONArray().apply { encoders.forEach { put(it.toJson()) } })
        .apply { probe?.let { put("probe", JSONObject().put("codec", it.codec.name).put("width", it.width).put("height", it.height).put("frames", it.frames).put("ms", it.ms).put("setupMs", it.setupMs)) } }

    companion object {
        fun fromJson(json: JSONObject): DeviceReport = DeviceReport(
            json.getString("key"),
            json.getJSONArray("encoders").let { array -> (0 until array.length()).map { encoderFromJson(array.getJSONObject(it)) } },
            json.optJSONObject("probe")?.let { p ->
                Codec.entries.firstOrNull { it.name == p.optString("codec") }?.let { ProbeResult(it, p.getInt("width"), p.getInt("height"), p.getInt("frames"), p.getLong("ms"), p.optLong("setupMs")) }
            },
        )
    }
}

private fun IntRange.toJson() = JSONArray().put(first).put(last)
private fun JSONArray?.toRange(): IntRange = if (this == null || length() < 2) 0..0 else getInt(0)..getInt(1)
private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

private fun EncoderCaps.toJson(): JSONObject = JSONObject().put("name", name).put("mime", mime).put("hardware", hardware)
    .put("widths", widths.toJson()).put("heights", heights.toJson()).put("widthAlign", widthAlign).put("heightAlign", heightAlign)
    .put("sizes", JSONArray().apply { sizes.forEach { put(JSONArray().put(it.width).put(it.height).put(it.maxFps)) } })
    .put("bitrates", bitrates.toJson())
    .put("profiles", JSONArray(profiles)).put("sampleRates", JSONArray(sampleRates)).put("maxChannels", maxChannels)

private fun encoderFromJson(o: JSONObject) = EncoderCaps(
    o.getString("name"), o.getString("mime"), o.getBoolean("hardware"),
    o.optJSONArray("widths").toRange(), o.optJSONArray("heights").toRange(), o.optInt("widthAlign", 1), o.optInt("heightAlign", 1),
    o.optJSONArray("sizes")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { SizePoint(it.getInt(0), it.getInt(1), it.getDouble(2)) } } }.orEmpty(),
    o.optJSONArray("bitrates").toRange(),
    o.optJSONArray("profiles").ints(), o.optJSONArray("sampleRates").ints(), o.optInt("maxChannels"),
)

class DeviceCapabilities private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("export_device", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val state = MutableStateFlow<DeviceReport?>(null)
    val report: StateFlow<DeviceReport?> = state.asStateFlow()

    val key: String by lazy {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
        "${Build.FINGERPRINT}|$version"
    }

    suspend fun load(): DeviceReport = lock.withLock {
        state.value?.let { return it }
        withContext(Dispatchers.Default) {
            val stored = prefs.getString("report", null)?.let { runCatching { DeviceReport.fromJson(JSONObject(it)) }.getOrNull() }
            (stored?.takeIf { it.key == key } ?: DeviceReport(key, analyseEncoders()).also(::store)).also { state.value = it }
        }
    }

    suspend fun runCheckNow(engine: EngineThread?, progress: (Float) -> Unit = {}): DeviceReport {
        val encoders = withContext(Dispatchers.Default) { analyseEncoders() }
        progress(0.3f)
        val probe = engine?.let { runCatching { probe(it) { p -> progress(0.3f + 0.7f * p) } }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }.getOrNull() }
        progress(1f)
        return lock.withLock { DeviceReport(key, encoders, probe ?: state.value?.probe).also { store(it); state.value = it } }
    }

    fun check(settings: ExportSettings, width: Int, height: Int, hasAudio: Boolean = true): Support =
        state.value?.check(settings, width, height, hasAudio) ?: formatProblem(settings, hasAudio)?.let { Support.Unsupported(it, settings.fixedFormat()) } ?: Support.Supported

    fun check(settings: ExportSettings, scene: Scene, hasAudio: Boolean = true): Support =
        exportSize(scene, settings.shortSide, even = true).let { (w, h) -> check(settings, w, h, hasAudio) }

    private fun store(report: DeviceReport) {
        prefs.edit().putString("report", report.toJson().toString()).apply()
    }

    suspend fun probe(engine: EngineThread, progress: (Float) -> Unit = {}): ProbeResult {
        val s = ExportSettings(1080, 30)
        val scene = Scene.parse(ProbeScene)
        val (w, h) = exportSize(scene, s.shortSide, even = true)
        val out = File(app.cacheDir, "probe-${System.nanoTime()}.mp4")
        try {
            val start = SystemClock.elapsedRealtime()
            var first = 0L
            java.io.RandomAccessFile(out, "rw").use { file ->
                exportVideo(engine, scene, ProbeScene, s, file.fd, null, progress = { if (first == 0L && it > 0f) first = SystemClock.elapsedRealtime(); progress(it) })
            }
            val ms = SystemClock.elapsedRealtime() - start
            return ProbeResult(s.codec, w, h, frameCount(scene, s.fps), ms, (first - start).coerceIn(0, ms - 1))
        } finally {
            out.delete()
        }
    }

    companion object {
        @Volatile private var instance: DeviceCapabilities? = null

        fun of(context: Context): DeviceCapabilities =
            instance ?: synchronized(this) { instance ?: DeviceCapabilities(context).also { instance = it } }

        private val ProbeScene = "<scene title=\"probe\" width=\"1080\" height=\"1920\" fps=\"30\" totalTime=\"999\" bgcolor=\"#ff1b1d27\">" +
            (0 until 6).joinToString("") { i ->
                "<shape id=\"${i + 1}\" label=\"p$i\" startTime=\"0\" endTime=\"999\" fillType=\"color\" s=\"${if (i % 2 == 0) ".circle" else ".rect"}\">" +
                    "<transform><location value=\"${180 + 360 * (i % 3)},${480 + 960 * (i / 3)},0\"/><rotation value=\"${15 * i}\"/></transform>" +
                    "<fillColor value=\"#ff${listOf("f24949", "49a0f2", "f2c849", "5ad17a", "c25af2", "f28c49")[i]}\"/>" +
                    "<property name=\"size\" type=\"vec2\" value=\"300,300\"/></shape>"
            } + "</scene>"
    }
}

internal val ProbeSizes = VideoResolutions.map { it.shortSide }.flatMap { side ->
    val long = (side * 16 / 9 + 1) and 1.inv()
    listOf(side to long, long to side, side to side)
}

internal fun analyseEncoders(): List<EncoderCaps> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    .filter { it.isEncoder && !it.isAlias }
    .flatMap { info -> info.supportedTypes.mapNotNull { mime -> runCatching { encoderCaps(info, mime) }.getOrNull() } }

private fun encoderCaps(info: MediaCodecInfo, mime: String): EncoderCaps? {
    val caps = info.getCapabilitiesForType(mime)
    caps.videoCapabilities?.let { v ->
        val sizes = ProbeSizes.filter { (w, h) -> v.isSizeSupported(w, h) }.map { (w, h) -> SizePoint(w, h, v.getSupportedFrameRatesFor(w, h).upper) }
        return EncoderCaps(
            info.name, mime, info.isHardwareAccelerated,
            v.supportedWidths.lower..v.supportedWidths.upper, v.supportedHeights.lower..v.supportedHeights.upper,
            v.widthAlignment, v.heightAlignment, sizes,
            v.bitrateRange.lower..v.bitrateRange.upper, caps.profileLevels.map { it.profile }.distinct(),
        )
    }
    caps.audioCapabilities?.let { a ->
        val rates = a.supportedSampleRates?.toList() ?: a.supportedSampleRateRanges.flatMap { r -> listOf(44100, 48000).filter { it in r.lower..r.upper } }
        return EncoderCaps(info.name, mime, info.isHardwareAccelerated, bitrates = a.bitrateRange.lower..a.bitrateRange.upper,
            sampleRates = rates, maxChannels = a.maxInputChannelCount)
    }
    return null
}
