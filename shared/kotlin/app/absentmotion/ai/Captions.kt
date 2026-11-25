package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.Context
import app.absentmotion.platform.captionTextHeight
import app.absentmotion.platform.loadCaptionLibrary
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.SincBank
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
import app.absentmotion.platform.drain
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.function.IntConsumer
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

data class Word(val text: String, val startMs: Int, val endMs: Int, val glued: Boolean = false)

data class Chunk(val text: String, val startMs: Int, val endMs: Int)

enum class CaptionPosition { Top, Middle, Bottom }

data class CaptionStyle(
    val font: String = FontName.Default.xml, val sizePercent: Int = 100, val color: Int = StyleColors.White,
    val outline: Boolean = true, val position: CaptionPosition = CaptionPosition.Bottom, val maxWords: Int = 4,
    val english: Boolean = false, val highPrecision: Boolean = false,
    val outlineColor: Int = StyleColors.Black, val outlineThickness: Int = 6,
)

private const val MAX_CAPTION_MS = 3000
private const val PAUSE_MS = 500
private const val HANG_MS = 150
private const val MIN_CAPTION_MS = 700
private const val SENTENCE_ENDS = ".?!…。？！"
private const val PHRASE_ENDS = ",;:、，；："

private val weakEnds = ("a an the of to in on at for from with by my your his her our their its and or but " +
    "o os as um uma de do da dos das em no na nos nas e ou que meu minha " +
    "el la los las un una del al en y mi tu su le les une du des et mon ma ton ta " +
    "der die das den dem ein eine einen und zu mit il lo gli di per con").split(' ').toSet()

private fun wordKey(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()

fun chunkWords(words: List<Word>, maxWords: Int): List<Chunk> {
    val most = maxWords.coerceAtLeast(1)
    val phrases = ArrayList<List<Word>>()
    var current = ArrayList<Word>()
    for (word in words) {
        val last = current.lastOrNull()
        if (last != null && (word.startMs - last.endMs > PAUSE_MS || SENTENCE_ENDS.any { last.text.endsWith(it) } ||
                current.size >= 2 && PHRASE_ENDS.any { last.text.endsWith(it) })) {
            phrases += current
            current = ArrayList()
        }
        current += word
    }
    if (current.isNotEmpty()) phrases += current
    val pieces = ArrayList<List<Word>>()
    fun split(run: List<Word>) {
        if (run.size == 1 || run.size <= most && run.last().endMs - run.first().startMs <= MAX_CAPTION_MS) {
            pieces += run
            return
        }
        val parts = maxOf(2, (run.size + most - 1) / most)
        val even = run.size.toFloat() / parts
        val cut = (1 until run.size).minBy { at ->
            val crowded = at > most || run.size - at > most * (parts - 1)
            kotlin.math.abs(at - even) - (run[at].startMs - run[at - 1].endMs) / 250f +
                (if (wordKey(run[at - 1].text) in weakEnds) 1.5f else 0f) + (if (crowded) 100f else 0f)
        }
        split(run.subList(0, cut))
        split(run.subList(cut, run.size))
    }
    phrases.forEach(::split)
    return pieces.mapIndexed { i, piece ->
        val start = piece.first().startMs
        val next = pieces.getOrNull(i + 1)?.first()?.startMs ?: Int.MAX_VALUE
        val end = minOf(maxOf(piece.last().endMs + HANG_MS, start + MIN_CAPTION_MS), next, start + MAX_CAPTION_MS)
        val text = piece.zipWithNext().joinToString("", piece.first().text) { (_, b) -> (if (b.glued) "" else " ") + b.text }
        Chunk(text, start, maxOf(end, start + 1))
    }
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
            "font" to style.font, "wrapWidth" to "$wrap", "align" to "center", "textMetrics" to "pixel")))
            .withTransform("location", width / 2f, captionY(width, height, style.position, tall), 0f)
            .withFill("color", style.color).withContent(chunk.text)
        if (!style.outline) layer else layer.withBorderAdded().let {
            val border = it.borders.last()
            it.withValue(border.colorRef, formatColor(style.outlineColor)).withValue(border.sizeRef, style.outlineThickness.coerceIn(0, 50).toString())
        }
    }
}

private val SrtTime = Regex("""(\d+):(\d{1,2}):(\d{1,2})(?:[,.](\d{1,3}))?""")
private val SrtTags = Regex("""</?\s*(?:i|b|u|s|font)\b[^>]*>|\{\\[^}]*\}""", RegexOption.IGNORE_CASE)

private fun srtMs(match: MatchResult): Int {
    val (h, m, s, f) = match.destructured
    return ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

fun srtText(bytes: ByteArray): String {
    val utf16 = bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
    return String(bytes, if (utf16) Charsets.UTF_16 else Charsets.UTF_8).removePrefix("\uFEFF")
}

fun srtCues(text: String): List<Chunk> {
    val lines = text.removePrefix("\uFEFF").split("\r\n", "\n", "\r")
    val cues = ArrayList<Chunk>()
    var i = 0
    while (i < lines.size) {
        val times = lines[i++].split("-->").takeIf { it.size == 2 }?.map { SrtTime.find(it) } ?: continue
        val start = times[0]?.let(::srtMs) ?: continue
        val end = times[1]?.let(::srtMs) ?: continue
        val body = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank() && "-->" !in lines[i]) body += lines[i++]
        if (i < lines.size && "-->" in lines[i] && body.lastOrNull()?.trim()?.all(Char::isDigit) == true) body.removeAt(body.lastIndex)
        val words = body.map { it.replace(SrtTags, "").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (words.isNotEmpty() && end > start) cues += Chunk(words, start, end)
    }
    return cues.sortedBy { it.startMs }
}

internal fun Scene.srtChunks(text: String, layerId: Long? = null): List<Chunk> {
    val clip = layerId?.let(::layer)
    val timing = clip?.let { app.absentmotion.model.VideoTiming(it) }
    fun place(ms: Int) = if (clip == null) ms else clip.startTime + timing!!.localMs(ms.toDouble()).roundToInt()
    val first = if (clip == null) 0 else frameAt(clip.startTime)
    val end = when {
        clip != null -> frameAt(clip.endTime) + 1
        totalTime > 0 -> lastFrame + 1
        else -> Int.MAX_VALUE
    }
    val cues = srtCues(text).mapNotNull { cue ->
        val a = place(cue.startMs); val b = place(cue.endMs)
        val from = frameAt(minOf(a, b))
        val to = maxOf(frameAt(maxOf(a, b)), from + 1).coerceAtMost(end)
        if (from.coerceAtLeast(first) < to) Triple(cue.text, from.coerceAtLeast(first), to) else null
    }
    val out = ArrayList<Chunk>()
    for ((a, b) in cues.flatMap { listOf(it.second, it.third) }.distinct().sorted().zipWithNext()) {
        val shown = cues.filter { it.second <= a && it.third >= b }.joinToString("\n") { it.first }
        if (shown.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.endMs == frameStart(a) && last.text == shown) out[out.lastIndex] = last.copy(endMs = frameStart(b))
        else out += Chunk(shown, frameStart(a), frameStart(b))
    }
    return out
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
    val input = scene.captionInput(layerId)
    val source = input.scene
    val startMs = input.startMs
    val endMs = input.endMs
    val mix = withContext(Dispatchers.IO) { OfflineMix.of(context, source, Resampler.ULTRA) } ?: error(AppText.get(R.string.ai_no_sound_to_caption))
    val windows = captionWindows(startMs, endMs)
    val words = ArrayList<Word>()
    try {
        withContext(Dispatchers.IO) { CaptionModel.verify(context, style.highPrecision) }
        windows.forEachIndexed { k, (keepFrom, keepTo) ->
            fun step(local: Float, label: Int) = progress((k + local) / windows.size, AppText.get(label))
            val from = maxOf(startMs, keepFrom - CAPTION_OVERLAP_MS)
            val pcm = withContext(Dispatchers.IO) { speechPcm(mix, from, keepTo) { step(0.15f * it, R.string.ai_reading_audio) } }
            words += Whisper.transcribe(CaptionModel.file(context, style.highPrecision), CaptionModel.heads(style.highPrecision), pcm, style.english) { step(0.15f + 0.85f * it / 100f, R.string.ai_recognising_speech) }
                .filter { it.startMs + from >= keepFrom && it.startMs + from < keepTo }
                .map { it.copy(startMs = it.startMs + from, endMs = min(it.endMs + from, endMs)) }
        }
    } finally {
        (mix as? Closeable)?.close()
    }
    return chunkWords(words, style.maxWords).map { it.copy(endMs = min(it.endMs, endMs)) }.ifEmpty { error(AppText.get(R.string.ai_no_speech)) }
}

internal data class CaptionInput(val scene: Scene, val startMs: Int, val endMs: Int)

internal fun Scene.captionInput(layerId: Long?): CaptionInput {
    if (layerId == null) return CaptionInput(this, 0, totalTime + 1)
    val layer = layer(layerId)?.takeIf { it.soundUri != null }
        ?: error(AppText.get(R.string.ai_no_sound_to_caption))
    val source = Scene(node.copy(children = node.children.filter { it.name !in Scene.LAYER_TAGS } +
        layer.node.with("hidden", null).let { it.copy(children = it.children.filterNot { c -> c.name == "gain" }) }))
    return CaptionInput(source, layer.startTime, layer.endTime + 1)
}

internal const val CAPTION_WINDOW_MS = 600_000
internal const val CAPTION_OVERLAP_MS = 2_000

internal fun captionWindows(startMs: Int, endMs: Int, windowMs: Int = CAPTION_WINDOW_MS): List<Pair<Int, Int>> =
    (startMs until maxOf(endMs, startMs + 1) step windowMs).map { it to minOf(endMs, it + windowMs) }

internal const val SPEECH_RATE = 16000
private const val SPEECH_CHUNK = SPEECH_RATE

internal suspend fun speechPcm(mix: AudioMix, startMs: Int, endMs: Int, progress: (Float) -> Unit): FloatArray {
    val out = FloatArray(speechSamples(startMs, endMs))
    var at = 0
    speechChunks(mix, startMs, endMs, progress) { chunk, count ->
        System.arraycopy(chunk, 0, out, at, count)
        at += count
    }
    return out
}

private fun speechSamples(startMs: Int, endMs: Int) = maxOf(SPEECH_RATE, ((endMs - startMs) * (SPEECH_RATE / 1000L)).toInt())

internal suspend fun speechChunks(mix: AudioMix, startMs: Int, endMs: Int, progress: (Float) -> Unit, sink: (FloatArray, Int) -> Unit) {
    val rate = ExportAudio.SAMPLE_RATE
    val step = rate / SPEECH_RATE.toDouble()
    val taps = Resampler.TAPS
    val total = speechSamples(startMs, endMs)
    val chunk = FloatArray(SPEECH_CHUNK)
    var held = 0
    val block = 4096
    val window = FloatArray((block + 4 * taps) * 2)
    var base = -2L * taps
    var filled = 2 * taps
    val first = Math.round(startMs / 1000.0 * rate)
    var fetched = 0L
    val pair = FloatArray(2)
    val sinc = SincBank()
    fun us(frame: Long) = Math.round(frame * 1e6 / rate)
    var i = 0
    while (i < total) {
        coroutineContext.ensureActive()
        val keep = ((i * step).toLong() - taps - base).toInt().coerceIn(0, filled)
        System.arraycopy(window, 2 * keep, window, 0, 2 * (filled - keep))
        base += keep
        filled -= keep
        val pcm = mix(us(first + fetched), us(first + fetched + block))
        for (k in pcm.indices) window[2 * filled + k] = pcm[k] / 32768f
        filled += pcm.size / 2
        fetched += pcm.size / 2
        while (i < total) {
            val position = i * step
            val j = position.toLong()
            if (j + taps >= base + filled) break
            Resampler.sample(window, (j - base).toInt(), (position - j).toFloat(), Resampler.ULTRA, step, pair, sinc)
            chunk[held++] = (pair[0] + pair[1]) / 2
            i++
            if (held == chunk.size) { sink(chunk, held); held = 0 }
        }
        progress(i.toFloat() / total)
    }
    if (held > 0) sink(chunk, held)
}

internal data class RawWord(val text: String, val t0: Int, val t1: Int, val dtw: Int, val glued: Boolean = false)

private val spacelessScripts = setOf(
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
    Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
)

private fun spaceless(char: Char) = Character.UnicodeScript.of(char.code) in spacelessScripts

private const val SPACELESS_PIECE = 4

private fun pieces(text: String, breaker: java.text.BreakIterator): List<String> {
    breaker.setText(text)
    val out = ArrayList<String>()
    var start = breaker.first()
    var end = breaker.next()
    while (end != java.text.BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = breaker.next()
    }
    return out
}

internal fun splitSpaceless(raw: List<RawWord>): List<RawWord> = raw.flatMap { word ->
    val text = word.text
    if (text.none(::spaceless)) return@flatMap listOf(word)
    val parts = ArrayList<String>()
    val spaced = HashSet<Int>()
    var gap = false
    for (piece in pieces(text, java.text.BreakIterator.getWordInstance())) when {
        piece.isBlank() -> gap = parts.isNotEmpty()
        piece.none { it.isLetterOrDigit() } && parts.isNotEmpty() && !gap -> parts[parts.lastIndex] += piece
        else -> {
            if (gap) spaced += parts.size
            gap = false
            val letters = pieces(piece, java.text.BreakIterator.getCharacterInstance())
            if (letters.size <= SPACELESS_PIECE || piece.none(::spaceless)) parts += piece
            else letters.chunked(SPACELESS_PIECE).mapTo(parts) { it.joinToString("") }
        }
    }
    if (parts.size < 2) return@flatMap listOf(word)
    val total = parts.sumOf { it.length }.toLong()
    var before = 0L
    parts.mapIndexed { i, part ->
        val t0 = word.t0 + ((word.t1 - word.t0) * before / total).toInt()
        before += part.length
        RawWord(part, t0, word.t0 + ((word.t1 - word.t0) * before / total).toInt(), if (i == 0) word.dtw else -1, i > 0 && i !in spaced)
    }
}

internal data class SpeechEdges(val onsets: List<Int>, val offsets: List<Int>)

internal fun speechEdges(pcm: FloatArray, rate: Int, frameMs: Int = 10, pauseMs: Int = 250): SpeechEdges {
    val frame = (rate * frameMs / 1000).coerceAtLeast(1)
    val levels = FloatArray(pcm.size / frame) { f ->
        var sum = 0.0
        for (i in f * frame until (f + 1) * frame) sum += pcm[i] * pcm[i]
        kotlin.math.sqrt(sum / frame).toFloat()
    }
    if (levels.isEmpty()) return SpeechEdges(emptyList(), emptyList())
    val loud = levels.sorted()[(levels.size * 0.95).toInt().coerceAtMost(levels.size - 1)]
    if (loud <= 1e-4f) return SpeechEdges(emptyList(), emptyList())
    val quiet = loud * 0.08f
    val needed = pauseMs / frameMs
    val onsets = ArrayList<Int>()
    val offsets = ArrayList<Int>()
    var silent = needed
    var lastLoud = -1
    for ((f, level) in levels.withIndex()) {
        if (level < quiet) {
            silent++
            if (silent == needed && lastLoud >= 0) offsets += (lastLoud + 1) * frameMs
        } else {
            if (silent >= needed) onsets += f * frameMs
            silent = 0
            lastLoud = f
        }
    }
    return SpeechEdges(onsets, offsets)
}

private fun squeezedRuns(starts: IntArray): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var i = 0
    while (i < starts.size) {
        var j = i
        while (j + 1 < starts.size && starts[j + 1] - starts[j] < SQUEEZED_MS) j++
        if (j - i + 1 >= SQUEEZED_RUN) runs += i..j
        i = j + 1
    }
    return runs
}

private fun dropRepeats(raw: List<RawWord>, timed: IntArray): Pair<List<RawWord>, IntArray> {
    val keys = raw.map { wordKey(it.text) }
    val starts = timed.copyOf()
    val drop = BooleanArray(raw.size)
    for (run in squeezedRuns(timed)) {
        for (k in run.first..run.last - 3) {
            val gram = keys.subList(k, k + 4)
            val elsewhere = (0..keys.size - 4).any { m ->
                (m + 4 <= run.first || m > run.last) && kotlin.math.abs(timed[m] - timed[k]) < REPEAT_WINDOW_MS && keys.subList(m, m + 4) == gram
            }
            if (elsewhere) for (d in k until k + 4) drop[d] = true
        }
        if (run.any { drop[it] }) continue
        val count = run.last - run.first + 1
        val from = timed[run.first]
        val until = minOf(timed.getOrNull(run.last + 1) ?: (from + SPREAD_MS * count), from + SPREAD_MS * count)
        val weights = run.map { keys[it].length.coerceAtLeast(1) }
        var before = 0
        for ((n, k) in run.withIndex()) {
            starts[k] = from + ((until - from).toLong() * before / weights.sum()).toInt()
            before += weights[n]
        }
    }
    val kept = raw.indices.filterNot { drop[it] }
    return kept.map { raw[it] } to IntArray(kept.size) { starts[kept[it]] }
}

private val phantoms = setOf(
    "you", "thank you", "thank you very much", "thanks for watching", "thank you for watching", "the end", "bye",
    "please subscribe", "subtitles by the amara org community", "obrigado", "obrigada", "obrigado por assistir", "gracias",
    "gracias por ver", "merci", "danke", "vielen dank", "продолжение следует", "ご視聴ありがとうございました",
)

private fun dropPhantoms(raw: List<RawWord>, starts: IntArray): List<Int> {
    val groups = ArrayList<MutableList<Int>>()
    for (i in raw.indices) {
        val last = groups.lastOrNull()
        if (last != null && starts[i] - starts[last.last()] < PHANTOM_GAP_MS) last += i else groups += mutableListOf(i)
    }
    return groups.filterNot { group ->
        group.joinToString("") { (if (it != group.first() && !raw[it].glued) " " else "") + wordKey(raw[it].text) }.replace(Regex("\\s+"), " ").trim() in phantoms
    }.flatten()
}

internal fun alignWords(raw: List<RawWord>, edges: SpeechEdges): List<Word> {
    if (raw.isEmpty()) return emptyList()
    val first = IntArray(raw.size) { i -> raw[i].let { if (it.dtw >= 0) maxOf(0, it.dtw - DTW_LEAD_MS) else it.t0 } }
    val (unique, timed) = dropRepeats(raw, first)
    val keep = dropPhantoms(unique, timed)
    if (keep.isEmpty()) return emptyList()
    return timeWords(keep.map { unique[it] }, IntArray(keep.size) { timed[keep[it]] }, edges)
}

private fun timeWords(raw: List<RawWord>, starts: IntArray, edges: SpeechEdges): List<Word> {
    for (i in 1 until starts.size) starts[i] = maxOf(starts[i], starts[i - 1] + MIN_WORD_GAP_MS)
    for (onset in edges.onsets) {
        val quietFrom = edges.offsets.lastOrNull { it < onset } ?: Int.MIN_VALUE
        val best = starts.indices.filter { starts[it] in maxOf(onset - SNAP_BEFORE_MS, quietFrom)..onset + SNAP_AFTER_MS }
            .minByOrNull { kotlin.math.abs(starts[it] - onset - SNAP_CENTER_MS) } ?: continue
        val after = if (best > 0) starts[best - 1] + MIN_WORD_GAP_MS else 0
        val before = if (best < starts.lastIndex) starts[best + 1] - MIN_WORD_GAP_MS else Int.MAX_VALUE
        if (onset in after..before) starts[best] = onset
    }
    return raw.indices.map { i ->
        val start = starts[i]
        val next = starts.getOrNull(i + 1)
        val pause = edges.offsets.firstOrNull { it > start && (next == null || it <= next) }
        val spoken = start + (WORD_MS_PER_LETTER * wordKey(raw[i].text).length).coerceIn(SHORTEST_WORD_MS, LONGEST_WORD_MS)
        val end = minOf(pause ?: Int.MAX_VALUE, next ?: Int.MAX_VALUE, if (raw[i].dtw >= 0 || next != null) spoken else maxOf(spoken, start + raw[i].t1 - raw[i].t0))
        Word(raw[i].text, start, maxOf(end, start + 1), raw[i].glued)
    }
}

private const val DTW_LEAD_MS = 180
private const val MIN_WORD_GAP_MS = 40
private const val SNAP_BEFORE_MS = 300
private const val SNAP_AFTER_MS = 750
private const val SNAP_CENTER_MS = 30
private const val SQUEEZED_MS = 60
private const val SQUEEZED_RUN = 3
private const val SPREAD_MS = 450
private const val REPEAT_WINDOW_MS = 30_000
private const val PHANTOM_GAP_MS = 2_000
private const val WORD_MS_PER_LETTER = 70
private const val SHORTEST_WORD_MS = 250
private const val LONGEST_WORD_MS = 900

internal object Whisper {
    init { loadCaptionLibrary() }
    private external fun nativeOpen(path: String, heads: Int): Long
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativeRun(handle: Long, pcm: FloatArray, english: Boolean, listener: IntConsumer): ByteArray?

    suspend fun transcribe(model: File, heads: Int, pcm: FloatArray, english: Boolean, progress: (Int) -> Unit): List<Word> = withContext(Dispatchers.Default) {
        val handle = nativeOpen(model.path, heads)
        check(handle != 0L) { AppText.get(R.string.ai_caption_model_load_failed) }
        try {
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { nativeCancel(handle) } }
            val text = try { nativeRun(handle, pcm, english) { progress(it) } } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
            ensureActive()
            val raw = checkNotNull(text) { AppText.get(R.string.ai_speech_failed) }.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                val word = parts.getOrNull(3)?.trim().orEmpty()
                if (word.isEmpty()) null else RawWord(word, parts[0].toInt(), parts[1].toInt(), parts[2].toLongOrNull()?.toInt() ?: -1)
            }.filter { (if (it.dtw >= 0) it.dtw else it.t0) < pcm.size / (SPEECH_RATE / 1000) }.toList()
            alignWords(splitSpaceless(raw), speechEdges(pcm, SPEECH_RATE))
        } finally {
            nativeClose(handle)
        }
    }
}

internal object CaptionModel {
    const val NAME = app.absentmotion.platform.CAPTION_MODEL_NAME

    const val BYTES = app.absentmotion.platform.CAPTION_MODEL_BYTES
    const val SHA256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"
    private const val RELEASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
    private const val PRECISE_NAME = "ggml-large-v3-turbo-q8_0.bin"
    private const val PRECISE_BYTES = 874_188_075L
    private const val PRECISE_SHA256 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1"
    private val OLD_NAMES = listOf("ggml-base-q5_1.bin", "ggml-base-q8_0.bin")

    fun name(highPrecision: Boolean) = if (highPrecision) PRECISE_NAME else NAME
    fun bytes(highPrecision: Boolean) = if (highPrecision) PRECISE_BYTES else BYTES
    fun heads(highPrecision: Boolean) = if (highPrecision) 2 else 1
    private fun hash(highPrecision: Boolean) = if (highPrecision) PRECISE_SHA256 else SHA256

    fun file(context: Context, highPrecision: Boolean = false) = File(context.filesDir, "models/${name(highPrecision)}")
    private fun oldFiles(context: Context) = OLD_NAMES.map { File(context.filesDir, "models/$it") }
    fun installed(context: Context, highPrecision: Boolean = false) = file(context, highPrecision).length() == bytes(highPrecision)
    fun storedBytes(context: Context) = file(context).length() + file(context, true).length() + oldFiles(context).sumOf { it.length() }
    fun remove(context: Context) { file(context).delete(); file(context, true).delete(); removeOld(context) }
    fun removeOld(context: Context) = oldFiles(context).forEach { it.delete() }

    fun verify(context: Context, highPrecision: Boolean = false) {
        val file = file(context, highPrecision)
        val known = context.getSharedPreferences("ai_models", Context.MODE_PRIVATE)
        val stamp = "${file.length()}:${file.lastModified()}"
        if (known.getString(file.path, null) == stamp) return
        val intact = file.length() == bytes(highPrecision) && file.inputStream().use(::sha256) == hash(highPrecision)
        if (!intact) file.delete()
        check(intact) { AppText.get(R.string.ai_caption_model_load_failed) }
        known.edit().putString(file.path, stamp).apply()
    }

    suspend fun download(context: Context, highPrecision: Boolean = false, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(context, highPrecision)
        val part = File(target.path + ".part")
        try {
            check(target.parentFile!!.let { it.isDirectory || it.mkdirs() }) { AppText.get(R.string.ai_cannot_create_model_storage) }
            app.absentmotion.platform.MobileData.check()
            val connection = URL("$RELEASE/${name(highPrecision)}").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { AppText.get(R.string.ai_download_failed_http, connection.responseCode.toString()) }
                connection.inputStream.use { input -> receive(input, part, target, bytes(highPrecision), hash(highPrecision), { ensureActive() }, progress) }
            } finally {
                connection.disconnect()
            }
        } finally {
            part.delete()
        }
    }

    internal fun receive(input: InputStream, part: File, target: File, bytes: Long, sha256: String, active: () -> Unit, progress: (Float) -> Unit,
                         append: Boolean = false, wrong: Int = R.string.ai_download_not_model) {
        val digest = MessageDigest.getInstance("SHA-256")
        if (append) part.inputStream().use { DigestInputStream(it, digest).drain() }
        val digesting = DigestInputStream(input, digest)
        java.io.FileOutputStream(part, append).use { output ->
            val buffer = ByteArray(64 * 1024)
            var done = if (append) part.length() else 0L
            while (true) {
                active()
                val count = digesting.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                done += count
                if (done > bytes) part.delete()
                check(done <= bytes) { AppText.get(wrong) }
                progress(done.toFloat() / bytes)
            }
        }
        check(part.length() == bytes) { AppText.get(R.string.ai_download_incomplete) }
        val intact = hex(digest.digest()) == sha256
        if (!intact) part.delete()
        check(intact) { AppText.get(wrong) }
        check(runCatching { java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }.isSuccess) { AppText.get(R.string.ai_cannot_save_model) }
    }

    internal fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, digest).drain()
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
