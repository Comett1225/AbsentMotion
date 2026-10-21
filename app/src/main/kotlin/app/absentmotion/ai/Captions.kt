package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.Context
import app.absentmotion.platform.captionTextHeight
import app.absentmotion.platform.loadCaptionLibrary
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.export.AudioMix
import app.absentmotion.export.ExportAudio
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.StyleColors
import app.absentmotion.model.XmlNode
import app.absentmotion.model.borders
import app.absentmotion.model.formatColor
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withBorderAdded
import app.absentmotion.model.withContent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.function.IntConsumer
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

data class Word(val text: String, val startMs: Int, val endMs: Int)

data class Chunk(val text: String, val startMs: Int, val endMs: Int)

enum class CaptionPosition { Top, Middle, Bottom }

data class CaptionStyle(
    val font: String = FontName.Default.xml, val sizePercent: Int = 100, val color: Int = StyleColors.White,
    val outline: Boolean = true, val position: CaptionPosition = CaptionPosition.Bottom, val maxWords: Int = 4,
    val english: Boolean = false,
)

private const val MAX_CAPTION_MS = 3000
private const val PAUSE_MS = 500
private const val SENTENCE_ENDS = ".?!…。？！"

fun chunkWords(words: List<Word>, maxWords: Int): List<Chunk> {
    val chunks = mutableListOf<Chunk>()
    var current = mutableListOf<Word>()
    fun flush() {
        if (current.isEmpty()) return
        val start = current.first().startMs
        chunks += Chunk(current.joinToString(" ") { it.text }, start, min(current.last().endMs, start + MAX_CAPTION_MS))
        current = mutableListOf()
    }
    for (word in words) {
        val last = current.lastOrNull()
        if (last != null && (current.size >= maxWords || word.endMs - current.first().startMs > MAX_CAPTION_MS ||
                word.startMs - last.endMs > PAUSE_MS || SENTENCE_ENDS.any { last.text.endsWith(it) })) flush()
        current += word
    }
    flush()
    return chunks
}

fun captionSize(width: Int, height: Int, percent: Int): Float = 0.06f * min(width, height) / 3f * percent / 100f

fun captionWrap(width: Int): Int = (width * 0.85f).roundToInt()

fun captionY(width: Int, height: Int, position: CaptionPosition, textHeight: Float): Float {
    val line = height * when (position) {
        CaptionPosition.Top -> 0.18f
        CaptionPosition.Middle -> 0.5f
        CaptionPosition.Bottom -> if (width > height) 0.85f else 0.82f
    }
    val reach = textHeight / 2 + height * 0.04f
    return if (2 * reach >= height) height / 2f else line.coerceIn(reach, height - reach)
}

internal fun Scene.captionLayers(
    chunks: List<Chunk>, style: CaptionStyle,
    name: (Int) -> String = { AppText.get(R.string.caption_layer_name, "$it") },
    measure: (text: String, size: Float) -> Float,
): List<Layer> {
    val head = name(0).substringBefore('0')
    val tail = name(0).substringAfter('0')
    val first = 1 + (layers.mapNotNull { l ->
        l.label.takeIf { it.length > head.length + tail.length && it.startsWith(head) && it.endsWith(tail) }
            ?.let { it.substring(head.length, it.length - tail.length).toIntOrNull() }
    }.maxOrNull() ?: 0)
    val wrap = captionWrap(width)
    val starts = chunks.map { frameAt(it.startMs) }
    return chunks.mapIndexed { i, chunk ->
        val lastFrame = maxOf(starts[i], min(frameAt(chunk.endMs - 1), (starts.getOrNull(i + 1) ?: Int.MAX_VALUE) - 1))
        var size = captionSize(width, height, style.sizePercent)
        var tall = measure(chunk.text, size)
        while (tall > height * 0.9f && size > 1f) { size *= 0.9f; tall = measure(chunk.text, size) }
        val layer = Layer(XmlNode("text", linkedMapOf("id" to "${Layer.newId()}", "label" to name(first + i),
            "startTime" to "${frameStart(starts[i])}", "endTime" to "${frameEnd(lastFrame)}",
            "fillType" to "color", "mediaFillMode" to "fill", "size" to formatFloats(floatArrayOf(size)),
            "font" to style.font, "wrapWidth" to "$wrap", "align" to "center")))
            .withTransform("location", width / 2f, captionY(width, height, style.position, tall), 0f)
            .withFill("color", style.color).withContent(chunk.text)
        if (!style.outline) layer else layer.withBorderAdded().let { it.withValue(it.borders.last().colorRef, formatColor(StyleColors.Black)) }
    }
}

internal fun EditorState.addCaptions(chunks: List<Chunk>, style: CaptionStyle, fonts: FontLibrary) {
    val wrap = captionWrap(scene.width)
    val layers = scene.captionLayers(chunks, style) { text, size -> captionTextHeight(fonts, text, style.font, size, wrap) }
    edit { s -> layers.fold(s) { acc, layer -> acc.add(layer) } }
    selectedId = null
    MultiSelect.ids = layers.map { it.id }.toSet()
    MultiSelect.active = true
    frame = scene.frameAt(layers.first().startTime)
    addOpen = false
}

internal suspend fun recogniseCaptions(
    context: Context, scene: Scene, layerId: Long?, style: CaptionStyle, progress: (Float, String) -> Unit,
): List<Chunk> {
    val layer = layerId?.let(scene::layer)?.takeIf { it.soundUri != null }
    val source = if (layer == null) scene else Scene(scene.node.copy(children = scene.node.children.filter { it.name !in Scene.LAYER_TAGS } +
        layer.node.with("hidden", null).let { it.copy(children = it.children.filterNot { c -> c.name == "gain" }) }))
    val startMs = layer?.startTime ?: 0
    val endMs = (layer?.endTime ?: scene.totalTime) + 1
    val mix = OfflineMix.of(context, source, Resampler.ULTRA) ?: error(AppText.get(R.string.ai_no_sound_to_caption))
    val pcm = try {
        withContext(Dispatchers.IO) { speechPcm(mix, startMs, endMs) { progress(0.15f * it, AppText.get(R.string.ai_reading_audio)) } }
    } finally {
        (mix as? Closeable)?.close()
    }
    withContext(Dispatchers.IO) { CaptionModel.verify(context) }
    val words = Whisper.transcribe(CaptionModel.file(context), pcm, style.english) { progress(0.15f + 0.85f * it / 100f, AppText.get(R.string.ai_recognising_speech)) }
    return chunkWords(words.map { Word(it.text, min(it.startMs + startMs, endMs - 1), min(it.endMs + startMs, endMs)) }, style.maxWords)
        .ifEmpty { error(AppText.get(R.string.ai_no_speech)) }
}

internal suspend fun speechPcm(mix: AudioMix, startMs: Int, endMs: Int, progress: (Float) -> Unit): FloatArray {
    val rate = ExportAudio.SAMPLE_RATE
    val step = rate / 16000.0
    val taps = Resampler.TAPS
    val out = FloatArray(maxOf(16000, ((endMs - startMs) * 16L).toInt()))
    val block = 4096
    val window = FloatArray((block + 4 * taps) * 2)
    var base = -2L * taps
    var filled = 2 * taps
    val first = Math.round(startMs / 1000.0 * rate)
    var fetched = 0L
    val pair = FloatArray(2)
    val scratch = FloatArray(2 * taps)
    fun us(frame: Long) = Math.round(frame * 1e6 / rate)
    var i = 0
    while (i < out.size) {
        coroutineContext.ensureActive()
        val keep = ((i * step).toLong() - taps - base).toInt().coerceIn(0, filled)
        System.arraycopy(window, 2 * keep, window, 0, 2 * (filled - keep))
        base += keep
        filled -= keep
        val pcm = mix(us(first + fetched), us(first + fetched + block))
        for (k in pcm.indices) window[2 * filled + k] = pcm[k] / 32768f
        filled += pcm.size / 2
        fetched += pcm.size / 2
        while (i < out.size) {
            val position = i * step
            val j = position.toLong()
            if (j + taps >= base + filled) break
            Resampler.sample(window, (j - base).toInt(), (position - j).toFloat(), Resampler.ULTRA, step, pair, scratch)
            out[i++] = (pair[0] + pair[1]) / 2
        }
        progress(i.toFloat() / out.size)
    }
    return out
}

internal object Whisper {
    init { loadCaptionLibrary() }
    private external fun nativeOpen(path: String): Long
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativeRun(handle: Long, pcm: FloatArray, english: Boolean, listener: IntConsumer): ByteArray?

    suspend fun transcribe(model: File, pcm: FloatArray, english: Boolean, progress: (Int) -> Unit): List<Word> = withContext(Dispatchers.Default) {
        val handle = nativeOpen(model.path)
        check(handle != 0L) { AppText.get(R.string.ai_caption_model_load_failed) }
        try {
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { nativeCancel(handle) } }
            val text = try { nativeRun(handle, pcm, english) { progress(it) } } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
            ensureActive()
            checkNotNull(text) { AppText.get(R.string.ai_speech_failed) }.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                val word = parts.getOrNull(2)?.trim().orEmpty()
                if (word.isEmpty()) null else Word(word, parts[0].toInt(), parts[1].toInt())
            }.toList()
        } finally {
            nativeClose(handle)
        }
    }
}

internal object CaptionModel {
    const val NAME = app.absentmotion.platform.CAPTION_MODEL_NAME

    const val BYTES = app.absentmotion.platform.CAPTION_MODEL_BYTES
    const val SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    private const val SOURCE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/$NAME"

    @Volatile private var checked: Pair<Long, Long>? = null

    fun file(context: Context) = File(context.filesDir, "models/$NAME")
    fun installed(context: Context) = file(context).length() == BYTES
    fun remove(context: Context) { file(context).delete() }

    fun verify(context: Context) {
        val file = file(context)
        val stamp = file.length() to file.lastModified()
        if (checked == stamp) return
        check(file.length() == BYTES && file.inputStream().use(::sha256) == SHA256) { AppText.get(R.string.ai_caption_model_load_failed) }
        checked = stamp
    }

    suspend fun download(context: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(context)
        val part = File(target.path + ".part")
        try {
            check(target.parentFile!!.let { it.isDirectory || it.mkdirs() }) { AppText.get(R.string.ai_cannot_create_model_storage) }
            val connection = URL(SOURCE).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { AppText.get(R.string.ai_download_failed_http, connection.responseCode.toString()) }
                connection.inputStream.use { input -> receive(input, part, target, BYTES, SHA256, { ensureActive() }, progress) }
            } finally {
                connection.disconnect()
            }
        } finally {
            part.delete()
        }
    }

    internal fun receive(input: InputStream, part: File, target: File, bytes: Long, sha256: String, active: () -> Unit, progress: (Float) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val digesting = DigestInputStream(input, digest)
        part.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                active()
                val count = digesting.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                done += count
                check(done <= bytes) { AppText.get(R.string.ai_download_not_model) }
                progress(done.toFloat() / bytes)
            }
        }
        check(part.length() == bytes) { AppText.get(R.string.ai_download_incomplete) }
        check(hex(digest.digest()) == sha256) { AppText.get(R.string.ai_download_not_model) }
        check(part.renameTo(target)) { AppText.get(R.string.ai_cannot_save_model) }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream())
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
