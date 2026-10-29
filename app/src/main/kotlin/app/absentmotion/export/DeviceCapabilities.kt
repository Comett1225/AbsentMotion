package app.absentmotion.export

import app.absentmotion.platform.versionCodeLong
import app.absentmotion.platform.hardware
import app.absentmotion.platform.alias
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import app.absentmotion.R
import app.absentmotion.Tier
import app.absentmotion.engine.EngineThread
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Scene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

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

    fun inRange(width: Int, height: Int): Boolean =
        width in widths && height in heights && width % widthAlign.coerceAtLeast(1) == 0 && height % heightAlign.coerceAtLeast(1) == 0

    fun fitsSize(width: Int, height: Int): Boolean = inRange(width, height) && covering(width, height).isNotEmpty()

    fun takesAudio(rate: Int, channels: Int, bitsPerSecond: Int?): Boolean =
        (sampleRates.isEmpty() || rate in sampleRates) && (maxChannels <= 0 || channels <= maxChannels) &&
            (bitsPerSecond == null || bitrates.last <= 0 || bitsPerSecond in bitrates)

    fun fits(width: Int, height: Int, fps: Int): Boolean = fitsSize(width, height) &&
        covering(width, height).any { it.maxFps * it.width * it.height >= fps.toDouble() * width * height * 0.999 }

    fun ratedFps(width: Int, height: Int): Double? = covering(width, height).maxOfOrNull { it.maxFps * it.width * it.height / (width.toDouble() * height) }
}

data class ProbeResult(val codec: Codec, val width: Int, val height: Int, val frames: Int, val ms: Long, val setupMs: Long = 0) {
    val pixelsPerSecond: Double get() = width.toDouble() * height * (frames - 1).coerceAtLeast(1) * 1000.0 / (ms - setupMs).coerceAtLeast(1)
}

sealed interface Support {
    data object Supported : Support
    data class SlowSoftwareOnly(val reason: UiText) : Support
    data class Unsupported(val reason: UiText, val suggestion: ExportSettings?, val sound: Boolean = false) : Support
}

data class DeviceReport(
    val key: String, val encoders: List<EncoderCaps>, val probe: ProbeResult? = null, val sdk: Int = Int.MAX_VALUE, val turns: Boolean = false,
) {
    fun encodersFor(mime: String): List<EncoderCaps> = encoders.filter { it.mime == mime }

    fun hardware(codec: Codec): Boolean = encodersFor(codec.mime).any { it.hardware }

    fun encoderFor(codec: Codec, width: Int, height: Int): EncoderCaps? =
        encodersFor(codec.mime).sortedByDescending { it.hardware }.firstOrNull { it.fitsSize(width, height) }

    fun encodeSize(s: ExportSettings, width: Int, height: Int): Pair<Int, Int> {
        val hardware = encodersFor(s.codec.mime).filter { it.hardware }
        val turned = turnsToLandscape(width, height, s.container, hardware.any { it.fitsSize(width, height) }, hardware.any { it.fitsSize(height, width) }, turns)
        return if (turned) height to width else width to height
    }

    val videoCodecs: List<Codec> get() = Codec.entries.filter { encodersFor(it.mime).isNotEmpty() }

    fun unconfirmed(width: Int, height: Int): List<EncoderCaps> = encoders.filter { it.video && it.inRange(width, height) && !it.fitsSize(width, height) }

    fun withSizes(found: Map<String, SizePoint>): DeviceReport =
        copy(encoders = encoders.map { e -> found["${e.name} ${e.mime}"]?.let { e.copy(sizes = e.sizes + it) } ?: e })

    fun check(s: ExportSettings, width: Int, height: Int, hasAudio: Boolean = true): Support {
        formatProblem(s, hasAudio, sdk)?.let { reason ->
            val fixed = s.fixedFormat(sdk)
            return Support.Unsupported(reason, if (formatProblem(fixed, hasAudio, sdk) == null) fixed else alternative(s, width, height))
        }
        val all = encodersFor(s.codec.mime)
        if (all.isEmpty()) {
            return Support.Unsupported(txt(R.string.export_support_no_encoder, s.codec.label), alternative(s, width, height))
        }
        val (encodeWidth, encodeHeight) = encodeSize(s, width, height)
        val sized = all.filter { it.fitsSize(encodeWidth, encodeHeight) }
        if (sized.isEmpty()) {
            return Support.Unsupported(txt(R.string.export_support_cannot_encode, s.codec.label, "${width}x$height"), alternative(s, width, height))
        }
        if (hasAudio && encodersFor(s.audio.codec.mime).isEmpty()) {
            return Support.Unsupported(txt(R.string.export_support_no_encoder, s.audio.codec.label), withAac(s), sound = true)
        }
        if (hasAudio && !takes(s.audio)) {
            return Support.Unsupported(txt(R.string.export_support_audio_settings, s.audio.codec.label), audioFix(s), sound = true)
        }
        if (sized.none { it.hardware }) {
            return Support.SlowSoftwareOnly(txt(R.string.export_support_software_only, s.codec.label))
        }
        if (sized.none { it.hardware && it.fits(encodeWidth, encodeHeight, s.fps) }) {
            return Support.SlowSoftwareOnly(txt(R.string.export_support_rated_below, s.codec.label, s.fps.toString(), "${width}x$height"))
        }
        return Support.Supported
    }

    fun checkAudioOnly(s: ExportSettings): UiText? {
        audioFormatProblem(s.audioFormat, sdk)?.let { return it }
        val (mime, label) = when (s.audioFormat) {
            AudioFormat.Flac -> "audio/flac" to "FLAC"
            AudioFormat.M4a -> AudioCodec.Aac.mime to AudioCodec.Aac.label
            AudioFormat.Ogg -> AudioCodec.Opus.mime to AudioCodec.Opus.label
            else -> return null
        }
        val found = encodersFor(mime)
        if (found.isEmpty()) return txt(R.string.export_support_no_encoder, label)
        val bits = if (s.audioFormat.lossless) null else s.audio.bitrateKbps * 1000
        return if (found.any { it.takesAudio(audioRate(s), s.audio.channels, bits) }) null else txt(R.string.export_support_audio_settings, label)
    }

    private fun takes(a: AudioSettings): Boolean = encodersFor(a.codec.mime).any { it.takesAudio(a.effectiveRate, a.channels, a.bitrateKbps * 1000) }

    private fun audioFix(s: ExportSettings): ExportSettings? {
        val a = s.audio
        val options = AudioSampleRates.sortedBy { it != a.sampleRate }.flatMap { rate ->
            listOf(a.channels, 3 - a.channels).flatMap { channels ->
                AudioBitratesKbps.sortedBy { kotlin.math.abs(it - a.bitrateKbps) }.map { a.copy(sampleRate = rate, channels = channels, bitrateKbps = it) }
            }
        }
        return options.firstOrNull(::takes)?.let { s.copy(audio = it) } ?: withAac(s).takeIf { takes(it.audio) }
    }

    private fun withAac(s: ExportSettings): ExportSettings {
        val container = s.container.takeIf { AudioCodec.Aac in audioCodecsFor(it) } ?: containersFor(s.codec, sdk).firstOrNull { AudioCodec.Aac in audioCodecsFor(it) } ?: Container.Mp4
        return s.copy(container = container, audio = s.audio.copy(codec = AudioCodec.Aac))
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
            order.firstOrNull { (codec, side) -> fit(codec, side, hardware) && formatProblem(s.copy(codec = codec).fixedFormat(sdk), false, sdk) == null }
                ?.let { (codec, side) -> return s.copy(codec = codec, shortSide = side).fixedFormat(sdk) }
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().put("key", key).put("sdk", sdk)
        .put("encoders", JSONArray().apply { encoders.forEach { put(it.toJson()) } })
        .apply { probe?.let { put("probe", JSONObject().put("codec", it.codec.name).put("width", it.width).put("height", it.height).put("frames", it.frames).put("ms", it.ms).put("setupMs", it.setupMs)) } }

    companion object {
        fun fromJson(json: JSONObject): DeviceReport = DeviceReport(
            json.getString("key"),
            json.getJSONArray("encoders").let { array -> (0 until array.length()).map { encoderFromJson(array.getJSONObject(it)) } },
            json.optJSONObject("probe")?.let { p ->
                Codec.entries.firstOrNull { it.name == p.optString("codec") }?.let { ProbeResult(it, p.getInt("width"), p.getInt("height"), p.getInt("frames"), p.getLong("ms"), p.optLong("setupMs")) }
            },
            json.optInt("sdk", Int.MAX_VALUE),
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
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionCodeLong }.getOrDefault(0L)
        "${Build.FINGERPRINT}|$version"
    }

    private val listing = Attempt(ListLimitMs, "am-encoder-list") { analyseEncoders() }
    private val confirmed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun load(): DeviceReport? = lock.withLock {
        state.value?.let { return it }
        val stored = withContext(Dispatchers.IO) { runCatching { prefs.getString("report", null)?.let { DeviceReport.fromJson(JSONObject(it)) } }.getOrNull() }
        (stored?.takeIf { it.key == key } ?: listing.get()?.let { DeviceReport(key, it, sdk = Build.VERSION.SDK_INT).also(::store) })
            ?.copy(turns = Tier.compatible)?.also { state.value = it }
    }

    suspend fun runCheckNow(engine: EngineThread?, progress: (Float) -> Unit = {}): DeviceReport {
        val encoders = listing.get() ?: state.value?.encoders ?: throw IllegalStateException("the encoder list is unavailable")
        progress(0.3f)
        val probe = engine?.let { probeWithin(ProbeLimitMs) { probe(it) { p -> progress(0.3f + 0.7f * p) } } }
        progress(1f)
        return lock.withLock { DeviceReport(key, encoders, probe ?: state.value?.probe, Build.VERSION.SDK_INT, Tier.compatible).also { store(it); state.value = it } }
    }

    suspend fun confirmSize(width: Int, height: Int) {
        val open = state.value?.unconfirmed(width, height).orEmpty().filter { confirmed.add("${it.name} ${it.mime} ${width}x$height") }
        if (open.isEmpty()) return
        val found = finishedWithin(ListLimitMs, "am-encoder-size") { exactSizes(open, width, height) } ?: return
        if (found.isNotEmpty()) lock.withLock { state.value?.let { state.value = it.withSizes(found) } }
    }

    fun check(settings: ExportSettings, width: Int, height: Int, hasAudio: Boolean = true): Support =
        state.value?.check(settings, width, height, hasAudio)
            ?: formatProblem(settings, hasAudio, Build.VERSION.SDK_INT)?.let { Support.Unsupported(it, settings.fixedFormat(Build.VERSION.SDK_INT)) } ?: Support.Supported

    fun check(settings: ExportSettings, scene: Scene, hasAudio: Boolean = true): Support =
        exportSize(scene, settings.shortSide, even = true).let { (w, h) -> check(settings, w, h, hasAudio) }

    fun checkAudioOnly(settings: ExportSettings): UiText? =
        state.value?.checkAudioOnly(settings) ?: audioFormatProblem(settings.audioFormat, Build.VERSION.SDK_INT)

    private fun store(report: DeviceReport) {
        runCatching { prefs.edit().putString("report", report.toJson().toString()).apply() }
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

internal const val ListLimitMs = 5_000L
internal const val ProbeLimitMs = 30_000L

internal suspend fun <T> finishedWithin(limitMs: Long, name: String, work: () -> T): T? {
    val result = CompletableFuture<T>()
    thread(name = name, isDaemon = true) {
        try { result.complete(work()) } catch (t: Throwable) { result.completeExceptionally(t) }
    }
    return try {
        runInterruptible(Dispatchers.IO) { result.get(limitMs, TimeUnit.MILLISECONDS) }
    } catch (e: TimeoutException) {
        null
    } catch (e: ExecutionException) {
        null
    }
}

internal class Attempt<T>(private val limitMs: Long, private val name: String, private val work: () -> T) {
    private var running: CompletableFuture<T>? = null

    suspend fun get(): T? {
        val future = synchronized(this) {
            running?.takeIf { !it.isDone } ?: CompletableFuture<T>().also { next ->
                running = next
                thread(name = name, isDaemon = true) { try { next.complete(work()) } catch (t: Throwable) { next.completeExceptionally(t) } }
            }
        }
        return try {
            runInterruptible(Dispatchers.IO) { future.get(limitMs, TimeUnit.MILLISECONDS) }
        } catch (e: TimeoutException) {
            null
        } catch (e: ExecutionException) {
            null
        }
    }
}

private fun exactSizes(open: List<EncoderCaps>, width: Int, height: Int): Map<String, SizePoint> {
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.associateBy { it.name }
    return open.mapNotNull { caps ->
        val video = infos[caps.name]?.takeIf { it.isEncoder }?.let { runCatching { it.getCapabilitiesForType(caps.mime).videoCapabilities }.getOrNull() }
        if (video == null || !video.isSizeSupported(width, height)) null
        else "${caps.name} ${caps.mime}" to SizePoint(width, height, runCatching { video.getSupportedFrameRatesFor(width, height).upper }.getOrDefault(0.0))
    }.toMap()
}

internal suspend fun <T> probeWithin(limitMs: Long, work: suspend () -> T): T? {
    val running = CoroutineScope(Dispatchers.Default).async { work() }
    return try {
        withTimeoutOrNull(limitMs) { running.await() }
    } catch (t: Throwable) {
        if (t is CancellationException && running.isActive) throw t
        null
    } finally {
        running.cancel()
    }
}

internal val ProbeSizes = VideoResolutions.map { it.shortSide }.flatMap { side ->
    listOf(16, 20, 21).flatMap { wide -> val long = (side * wide / 9 + 1) and 1.inv(); listOf(side to long, long to side) } + (side to side)
}

internal fun analyseEncoders(): List<EncoderCaps> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    .filter { it.isEncoder && !it.alias }
    .flatMap { info -> info.supportedTypes.mapNotNull { mime -> runCatching { encoderCaps(info, mime) }.getOrNull() } }

private fun encoderCaps(info: MediaCodecInfo, mime: String): EncoderCaps? {
    val caps = info.getCapabilitiesForType(mime)
    caps.videoCapabilities?.let { v ->
        val sizes = ProbeSizes.filter { (w, h) -> v.isSizeSupported(w, h) }.map { (w, h) -> SizePoint(w, h, v.getSupportedFrameRatesFor(w, h).upper) }
        return EncoderCaps(
            info.name, mime, info.hardware,
            v.supportedWidths.lower..v.supportedWidths.upper, v.supportedHeights.lower..v.supportedHeights.upper,
            v.widthAlignment, v.heightAlignment, sizes,
            v.bitrateRange.lower..v.bitrateRange.upper, caps.profileLevels.map { it.profile }.distinct(),
        )
    }
    caps.audioCapabilities?.let { a ->
        val rates = a.supportedSampleRates?.toList() ?: a.supportedSampleRateRanges.flatMap { r -> listOf(44100, 48000).filter { it in r.lower..r.upper } }
        return EncoderCaps(info.name, mime, info.hardware, bitrates = a.bitrateRange.lower..a.bitrateRange.upper,
            sampleRates = rates, maxChannels = a.maxInputChannelCount)
    }
    return null
}
