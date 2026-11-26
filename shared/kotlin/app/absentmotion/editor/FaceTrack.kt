package app.absentmotion.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.R
import app.absentmotion.ai.displayTurn
import app.absentmotion.i18n.AppText
import app.absentmotion.media.FaceDetector
import app.absentmotion.media.FaceSpot
import app.absentmotion.media.FrameReader
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.valueAt
import app.absentmotion.model.videoTimeUs
import app.absentmotion.model.withEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

internal const val FACE_EFFECT = "absent.facetrack"
private const val SMOOTH_SECONDS = 1f
private const val DEFAULT_SMOOTHING = 0.3f
private const val PATH_TOLERANCE = 0.0005f

internal class FacePick(val pts: Long, val spot: FaceSpot)

internal class FaceSource(
    val uri: String, val width: Int, val height: Int, val pick: FacePick,
    val from: Long, val to: Long, val samples: List<Pair<Long, FaceSpot>>,
) {
    fun at(pts: Long): FloatArray {
        val i = samples.binarySearch { it.first.compareTo(pts) }
        if (i >= 0) return norm(samples[i].second)
        val next = -i - 1
        if (next == 0) return norm(samples.first().second)
        if (next == samples.size) return norm(samples.last().second)
        val (pa, a) = samples[next - 1]
        val (pb, b) = samples[next]
        val u = (pts - pa).toFloat() / (pb - pa)
        return floatArrayOf((a.x + (b.x - a.x) * u) / width, (a.y + (b.y - a.y) * u) / height, (a.size + (b.size - a.size) * u) / height)
    }

    private fun norm(s: FaceSpot) = floatArrayOf(s.x / width, s.y / height, s.size / height)

    fun node(): XmlNode = XmlNode("facepath", linkedMapOf(
        "uri" to uri, "w" to "$width", "h" to "$height", "size" to number(pick.spot.size / height),
        "pick" to "${pick.pts},${number(pick.spot.x / width)},${number(pick.spot.y / height)},${number(pick.spot.size / height)}",
        "from" to "$from", "to" to "$to",
        "src" to samples.joinToString(" ") { (p, s) -> "$p,${number(s.x / width)},${number(s.y / height)},${number(s.size / height)}" },
    ))
}

private fun number(v: Float) = String.format(Locale.US, "%.5f", v)

internal fun XmlNode.faceSource(): FaceSource? {
    val n = child("facepath") ?: return null
    val uri = n["uri"] ?: return null
    val w = n["w"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val h = n["h"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    fun spot(x: String, y: String, s: String): FaceSpot? {
        val size = s.finiteFloat()?.takeIf { it > 0 } ?: return null
        return FaceSpot((x.finiteFloat() ?: return null) * w, (y.finiteFloat() ?: return null) * h, size * h)
    }
    val pick = n["pick"]?.split(',')?.takeIf { it.size == 4 }?.let { p -> spot(p[1], p[2], p[3])?.let { FacePick(p[0].toLongOrNull() ?: return null, it) } } ?: return null
    val samples = n["src"].orEmpty().split(' ').mapNotNull { e ->
        val p = e.split(',').takeIf { it.size == 4 } ?: return@mapNotNull null
        spot(p[1], p[2], p[3])?.let { s -> p[0].toLongOrNull()?.let { it to s } }
    }.sortedBy { it.first }
    return FaceSource(uri, w, h, pick, n["from"]?.toLongOrNull() ?: pick.pts, n["to"]?.toLongOrNull() ?: pick.pts, samples)
}

internal fun matchFace(last: FaceSpot, gap: Int, found: List<FaceSpot>): FaceSpot? {
    val reach = last.size * minOf(0.5f + 0.35f * gap, 3f)
    return found.filter { it.size in last.size * 0.6f..last.size * 1.7f && hypot(it.x - last.x, it.y - last.y) <= reach }
        .minByOrNull { hypot(it.x - last.x, it.y - last.y) / last.size + abs(ln(it.size / last.size)) }
}

internal suspend fun followFace(
    pick: FacePick, wanted: List<Long>,
    read: suspend (List<Long>, suspend (Long, List<FaceSpot>) -> Unit) -> Unit,
    progress: suspend (Float) -> Unit = {},
): List<Pair<Long, FaceSpot>> {
    val all = (wanted + pick.pts).distinct().sorted()
    val runs = listOf(all.filter { it >= pick.pts }, all.filter { it <= pick.pts }.reversed())
    val total = runs.sumOf { it.size }.coerceAtLeast(1)
    val kept = HashMap<Long, FaceSpot>()
    var done = 0
    for (run in runs) {
        var last = pick.spot
        var gap = 0
        for (batch in run.chunked(BATCH)) {
            val found = HashMap<Long, List<FaceSpot>>()
            read(batch.sorted()) { p, faces -> found[p] = faces }
            for (p in batch) {
                val match = matchFace(last, gap, found[p].orEmpty())
                if (match == null) gap++ else {
                    kept[p] = match
                    last = match
                    gap = 1
                }
            }
            done += batch.size
            progress(done.toFloat() / total)
        }
    }
    return kept.entries.sortedBy { it.key }.map { it.key to it.value }
}

internal fun smoothFace(values: List<FloatArray>, sigmas: FloatArray): List<FloatArray> {
    val kernels = HashMap<Float, FloatArray>()
    return values.indices.map { i -> smoothAt(values, i, sigmas[i], kernels) }
}

private fun smoothAt(values: List<FloatArray>, i: Int, sigma: Float, kernels: HashMap<Float, FloatArray>): FloatArray {
    if (!(sigma >= 0.5f)) return values[i]
    val reach = ceil(3 * sigma).toInt()
    val kernel = kernels.getOrPut(sigma) { FloatArray(reach + 1) { d -> exp(-(d * d) / (2 * sigma * sigma)) } }
    val out = FloatArray(values[i].size)
    var weight = 0f
    for (j in maxOf(0, i - reach)..minOf(values.lastIndex, i + reach)) {
        val w = kernel[kotlin.math.abs(j - i)]
        weight += w
        for (c in out.indices) out[c] += values[j][c] * w
    }
    for (c in out.indices) out[c] /= weight
    return out
}

private val Layer.followsFaceOf: Boolean get() = node["matteFrame"] != null

private fun Layer.faceFrames(scene: Scene): IntRange = scene.firstShownFrame(startTime)..scene.lastShownFrame(endTime)

internal fun Scene.faceKey(layer: Layer, effect: XmlNode): String = listOf(
    fps, layer.endTime - layer.startTime, layer.mediaFill?.uri, layer.node["inTime"], layer.node["outTime"], layer.node["speed"], layer.node["loop"],
    layer.node["loopIn"], layer.node.child("speedMap"), layer.node.child("timeRemap"),
    layer.effects.firstOrNull { it["id"] == "absent.timequant" && it["hidden"] != "true" }, effect.effectProperty("smoothing"),
).hashCode().toUInt().toString(16)

internal fun Scene.facePath(layer: Layer, effect: XmlNode, source: FaceSource): String {
    val frames = layer.faceFrames(this).toList()
    if (frames.isEmpty() || source.samples.isEmpty()) return ""
    val span = (layer.endTime - layer.startTime).coerceAtLeast(1).toFloat()
    val timing = VideoTiming(layer)
    val smoothing = effect.effectProperty("smoothing")
    val times = FloatArray(frames.size) { (frameTime(frames[it]) - layer.startTime) / span }
    val raw = frames.map { source.at(layer.videoTimeUs(frameStart(it), this, timing)) }
    val sigmas = FloatArray(frames.size) { (smoothing?.valueAt(times[it])?.finiteFloat() ?: DEFAULT_SMOOTHING).coerceIn(0f, 1f) * fps * SMOOTH_SECONDS }
    val smooth = smoothFace(raw, sigmas)
    return simplifyKeys(times, smooth, PATH_TOLERANCE).joinToString(" ") { i ->
        String.format(Locale.US, "%.6f,%.5f,%.5f,%.5f", times[i], smooth[i][0], smooth[i][1], smooth[i][2])
    }
}

internal enum class FaceState { NotVideo, NoFace, Partial, Ready }

internal fun Scene.faceState(layer: Layer, effect: XmlNode): FaceState {
    val fill = layer.mediaFill?.takeIf { it.video } ?: return FaceState.NotVideo
    val source = effect.faceSource()?.takeIf { it.uri == fill.uri || layer.followsFaceOf } ?: return FaceState.NoFace
    val timing = VideoTiming(layer)
    val needed = layer.faceFrames(this).map { layer.videoTimeUs(frameStart(it), this, timing) }
    if (needed.isEmpty()) return FaceState.Ready
    val slack = 1_000_000L / fps
    return if (needed.min() >= source.from - slack && needed.max() <= source.to + slack) FaceState.Ready else FaceState.Partial
}

private fun Scene.followed(layer: Layer, effect: XmlNode): XmlNode {
    if (effect["id"] != FACE_EFFECT) return effect
    val path = effect.child("facepath") ?: return effect
    if (path["uri"] != layer.mediaFill?.takeIf { it.video }?.uri && !layer.followsFaceOf) return effect.withChild("facepath", null)
    val key = faceKey(layer, effect)
    if (path["key"] == key) return effect
    val source = effect.faceSource() ?: return effect.withChild("facepath", null)
    return effect.withChild("facepath", path.with("key", key).with("path", facePath(layer, effect, source)))
}

internal fun Scene.withFacePathsFollowing(): Scene {
    var next = this
    for (layer in layers) {
        val effects = layer.effects
        if (effects.none { it["id"] == FACE_EFFECT && it.child("facepath") != null }) continue
        val changed = effects.map { followed(layer, it) }
        if (changed.indices.all { changed[it] === effects[it] }) continue
        next = next.update(layer.id) { l -> changed.indices.fold(l) { acc, i -> acc.withEffect(i) { changed[i] } } }
    }
    return next
}

internal fun Scene.withFaceSource(layerId: Long, index: Int, source: FaceSource): Scene = update(layerId) { layer ->
    layer.withEffect(index) { effect ->
        if (effect["id"] != FACE_EFFECT) effect
        else effect.withChild("facepath", source.node().with("key", faceKey(layer, effect)).with("path", facePath(layer, effect, source)))
    }
}

class FaceMode(layerId: Long, val effectIndex: Int) : DrawingMode(layerId, created = false) {
    internal var faces by mutableStateOf<List<FaceSpot>?>(null)
    var facesFrame by mutableIntStateOf(-1)
    internal var facesPts = 0L
    var looking by mutableStateOf(false)
    var running by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf("")
    internal var again: FacePick? = null
    internal var job: Job? = null

    override val handlesCode: Int get() = -11
}

fun EditorState.pickFace(effectIndex: Int, again: Boolean = false) {
    val layer = selected?.takeIf { it.mediaFill?.video == true } ?: return
    if (layer.effects.getOrNull(effectIndex)?.get("id") != FACE_EFFECT) return
    if (sampleMs !in layer.startTime..layer.endTime) frame = scene.firstShownFrame(layer.startTime)
    playing = false
    val mode = FaceMode(layer.id, effectIndex).apply { if (again) this.again = layer.effects[effectIndex].faceSource()?.pick }
    drawing = mode
    previewChange = { s -> s.update(layer.id) { l -> l.withEffect(effectIndex) { it.with("hidden", "true") } } }
}

internal suspend fun EditorState.lookForFaces(context: Context, mode: FaceMode, at: Int) {
    val document = scene
    val layer = document.layer(mode.layerId) ?: return
    val uri = layer.mediaFill?.uri ?: return
    val media = document.media(uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return
    mode.faces = null
    mode.facesFrame = -1
    if (document.frameStart(at) !in layer.startTime..layer.endTime) {
        mode.message = AppText.get(R.string.track_motion_playhead_outside)
        return
    }
    mode.looking = true
    try {
        val (pts, found) = withContext(Dispatchers.IO) {
            FrameReader(context, uri).use { reader ->
                FaceDetector.open(context).use { detector ->
                    val pts = document.framePts(layer, listOf(at), reader.timestamps).single()
                    var faces = emptyList<FaceSpot>()
                    reader.read(listOf(pts)) { _, yuv -> faces = detector.faces(yuv, displayTurn(media.orientation), media.width, media.height) }
                    pts to faces
                }
            }
        }
        mode.facesPts = pts
        mode.faces = found
        mode.facesFrame = at
        if (!mode.running) mode.message = AppText.get(if (found.isEmpty()) R.string.face_tracking_none_here else R.string.face_tracking_tap)
    } catch (e: CancellationException) {
        throw e
    } catch (_: LinkageError) {
        mode.message = AppText.get(R.string.track_motion_unavailable)
    } catch (e: Exception) {
        mode.message = app.absentmotion.i18n.failureText(AppText.get(R.string.track_motion_failed), e)
    } finally {
        mode.looking = false
    }
}

internal fun EditorState.runFaceTracking(context: Context, mode: FaceMode, pick: FacePick, scope: CoroutineScope) {
    if (mode.running) return
    val document = scene
    val video = document.layer(mode.layerId) ?: return
    val uri = video.mediaFill?.takeIf { it.video }?.uri ?: return
    val media = document.media(uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return
    val frames = video.faceFrames(document).toList()
    if (frames.isEmpty()) return
    mode.running = true
    mode.progress = 0f
    mode.message = ""
    mode.job = scope.launch {
        try {
            val (wanted, samples) = withContext(Dispatchers.IO) {
                FrameReader(context, uri).use { reader ->
                    FaceDetector.open(context).use { detector ->
                        val wanted = (document.framePts(video, frames, reader.timestamps) + pick.pts).distinct().sorted()
                        val turn = displayTurn(media.orientation)
                        wanted to followFace(pick, wanted, { batch, deliver ->
                            reader.read(batch) { p, yuv -> deliver(p, detector.faces(yuv, turn, media.width, media.height)) }
                        }) { fraction -> withContext(Dispatchers.Main) { mode.progress = fraction } }
                    }
                }
            }
            if (samples.isEmpty()) {
                mode.message = AppText.get(R.string.face_tracking_lost)
                return@launch
            }
            val timing = VideoTiming(video)
            val times = frames.map { video.videoTimeUs(document.frameStart(it), document, timing) }
            val source = FaceSource(uri, media.width, media.height, pick, minOf(wanted.first(), times.min()), maxOf(wanted.last(), times.max()), samples)
            edit { s -> if (s.layer(video.id)?.mediaFill?.uri != uri) s else s.withFaceSource(video.id, mode.effectIndex, source) }
            if (drawing === mode) leaveDrawing()
        } catch (_: CancellationException) {
            mode.message = AppText.get(R.string.face_tracking_cancelled)
        } catch (_: LinkageError) {
            mode.message = AppText.get(R.string.track_motion_unavailable)
        } catch (e: Exception) {
            mode.message = app.absentmotion.i18n.failureText(AppText.get(R.string.track_motion_failed), e)
        } finally {
            mode.running = false
        }
    }
}
