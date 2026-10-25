package app.absentmotion.editor

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.R
import app.absentmotion.Tier
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.Smoothing
import app.absentmotion.model.Scene
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIB = 1L shl 20
private const val MAX_BUDGET = 256 * MIB
private const val MAX_SIDE = 1920
private const val ESTIMATE_PER_MILLE = 150L
private const val QUALITY = 85
private const val AHEAD = 2
private const val POOL = AHEAD + 3
private const val TAG = "AMCache"

fun previewCacheBudget(memoryClassMb: Int, lowRam: Boolean, compatible: Boolean): Long {
    val percent = when { lowRam -> 8; compatible -> 12; else -> 15 }
    return minOf(MAX_BUDGET, memoryClassMb.coerceAtLeast(16) * MIB * percent / 100)
}

fun storeBudget(budget: Long, width: Int, height: Int): Long = maxOf(budget / 4, budget - POOL * 4L * width * height)

fun cacheFrameSize(sceneWidth: Int, sceneHeight: Int, fit: Float, divisor: Int): Pair<Int, Int> {
    val longest = maxOf(sceneWidth, sceneHeight, 1)
    val cap = minOf(1f, MAX_SIDE.toFloat() / longest)
    val scale = if (fit > 0f) minOf(fit / divisor.coerceAtLeast(1), cap) else cap / divisor.coerceAtLeast(1)
    return maxOf(1, (sceneWidth * scale).roundToInt()) to maxOf(1, (sceneHeight * scale).roundToInt())
}

fun framesThatFit(budget: Long, width: Int, height: Int): Int =
    (budget / maxOf(1L, width.toLong() * height * ESTIMATE_PER_MILLE / 1000)).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

fun renderRange(lastFrame: Int, playhead: Int, maxFrames: Int): IntRange {
    val total = lastFrame.coerceAtLeast(0) + 1
    if (maxFrames >= total) return 0 until total
    val count = maxFrames.coerceAtLeast(1)
    val start = playhead.coerceIn(0, total - count)
    return start until start + count
}

fun flattenOnChecker(rgba: ByteArray, width: Int, height: Int, cell: Float): Boolean {
    val size = cell.coerceAtLeast(1f)
    var translucent = false
    var i = 3
    for (y in 0 until height) {
        val row = ((height - y - 0.5f) / size).toInt()
        for (x in 0 until width) {
            val a = rgba[i].toInt() and 0xff
            if (a != 0xff) {
                translucent = true
                val base = if ((((x + 0.5f) / size).toInt() + row) and 1 == 1) 0xcc else 0xe5
                for (c in 1..3) rgba[i - c] = (((rgba[i - c].toInt() and 0xff) * a + base * (255 - a) + 127) / 255).toByte()
                rgba[i] = -1
            }
            i += 4
        }
    }
    return translucent
}

class FrameStore(val first: Int, private val budget: Long) {
    private val frames = ArrayList<ByteArray>()
    var bytes = 0L
        private set
    val count: Int get() = frames.size
    val last: Int get() = first + frames.size - 1

    fun add(frame: ByteArray): Boolean {
        if (bytes + frame.size > budget) return false
        frames += frame
        bytes += frame.size
        return true
    }

    operator fun contains(frame: Int): Boolean = frame >= first && frame - first < frames.size

    operator fun get(frame: Int): ByteArray? = if (frame in this) frames[frame - first] else null
}

data class CacheKey(val projectId: String, val scene: Scene, val divisor: Int, val androidEmoji: Boolean, val resources: Long = 0)

class ChoppyWatch(private val windowNs: Long = 1_000_000_000L, private val windows: Int = 3, private val ratio: Float = 0.75f, private val minAdvance: Int = 5) {
    private var startNs = -1L
    private var startFrame = 0
    private var startRenders = 0L
    private var warmed = false
    private var bad = 0

    fun reset() {
        startNs = -1L
        warmed = false
        bad = 0
    }

    fun sample(nowNs: Long, frame: Int, renders: Long): Boolean {
        if (startNs < 0 || frame < startFrame) {
            startNs = nowNs; startFrame = frame; startRenders = renders
            return false
        }
        if (nowNs - startNs < windowNs) return false
        val advanced = frame - startFrame
        val shown = renders - startRenders
        if (!warmed) warmed = true
        else bad = if (advanced >= minAdvance && shown < advanced * ratio) bad + 1 else 0
        startNs = nowNs; startFrame = frame; startRenders = renders
        return bad >= windows
    }
}

class FramePlayer internal constructor(private val store: FrameStore, val width: Int, val height: Int) {
    class Shown(val frame: Int, val bitmap: Bitmap, val at: Long)

    var shown by mutableStateOf<Shown?>(null)
        private set
    @Volatile var shows = 0L
        private set
    private val thread = HandlerThread("am-cache-decode").apply { start() }
    private val handler = Handler(thread.looper)
    private val decoded = HashMap<Int, Bitmap>()
    private val recent = ArrayDeque<Int>()
    @Volatile private var wanted = -1
    @Volatile private var ahead = false
    @Volatile private var closed = false
    private val work = Runnable { decodeWanted() }

    fun want(frame: Int, playing: Boolean) {
        if (closed) return
        wanted = frame
        ahead = playing
        val ready = synchronized(this) { decoded[frame] }
        if (ready != null) show(frame, ready)
        if (ready == null || playing) {
            handler.removeCallbacks(work)
            handler.post(work)
        }
    }

    private fun show(frame: Int, bitmap: Bitmap) {
        synchronized(this) {
            if (recent.lastOrNull() != frame) { recent.addLast(frame); shows++ }
            while (recent.size > 2) recent.removeFirst()
        }
        shown = Shown(frame, bitmap, System.nanoTime())
    }

    private fun decodeWanted() {
        val target = wanted
        if (target < 0 || closed) return
        decode(target)?.let { if (wanted == target) show(target, it) }
        if (!ahead) return
        for (k in 1..AHEAD) {
            if (wanted != target || closed) return
            decode(target + k)
        }
    }

    private fun decode(frame: Int): Bitmap? {
        synchronized(this) { decoded[frame] }?.let { return it }
        val bytes = store[frame] ?: return null
        val reuse = synchronized(this) { victim() }
        val options = BitmapFactory.Options().apply { inMutable = true; inBitmap = reuse }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: IllegalArgumentException) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inMutable = true })
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        synchronized(this) { if (!closed) decoded[frame] = bitmap }
        return bitmap
    }

    private fun victim(): Bitmap? {
        if (decoded.size < POOL) return null
        val target = wanted
        val frame = decoded.keys.filter { it !in recent && it !in target..target + AHEAD }.maxByOrNull { abs(it - target) } ?: return null
        return decoded.remove(frame)
    }

    internal fun drop() {
        synchronized(this) { decoded.clear(); recent.clear() }
        shown = null
    }

    internal fun close() {
        closed = true
        handler.removeCallbacks(work)
        thread.quitSafely()
        drop()
    }
}

object PreviewCache {
    enum class Phase { Empty, Rendering, Ready }
    private enum class Outcome { Done, Full, Stopped, Failed, NoMemory }

    var phase by mutableStateOf(Phase.Empty)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var key by mutableStateOf<CacheKey?>(null)
        private set
    var player by mutableStateOf<FramePlayer?>(null)
        private set
    @Volatile private var store: FrameStore? = null
    @Volatile private var stopRequested = false
    private var job: Job? = null
    private var generation = 0
    @Volatile var fit = 0f
    @Volatile var checkerCell = 25f
    var hinted = 0

    val first: Int get() = store?.first ?: 0
    val last: Int get() = store?.last ?: -1

    fun covers(frame: Int): Boolean = store?.contains(frame) == true

    fun keyOf(state: EditorState, resources: Long = state.engine.mediaRevision.value) =
        CacheKey(state.projectId, state.scene, state.settings.previewQuality.divisor, state.settings.androidEmoji, resources)

    fun render(state: EditorState, context: Context, scope: CoroutineScope) {
        if (phase == Phase.Rendering) return
        val app = context.applicationContext
        val scene = state.scene
        val divisor = state.settings.previewQuality.divisor
        val (width, height) = cacheFrameSize(scene.width, scene.height, fit, divisor)
        val manager = app.getSystemService(ActivityManager::class.java)
        val budget = storeBudget(previewCacheBudget(manager?.memoryClass ?: 128, manager?.isLowRamDevice == true, Tier.compatible), width, height)
        val range = renderRange(scene.lastFrame, state.frame, framesThatFit(budget, width, height)).first..maxOf(scene.lastFrame, 0)
        val next = keyOf(state)
        val cell = checkerCell
        clear()
        state.playing = false
        val mine = generation
        phase = Phase.Rendering
        job = scope.launch {
            try {
                val started = System.nanoTime()
                val built = FrameStore(range.first, budget)
                var resources = next.resources
                val outcome = try {
                    withContext(Dispatchers.Default) { renderFrames(state.engine, scene, range, width, height, cell, built) { resources = it } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    Outcome.NoMemory
                } catch (e: Exception) {
                    Log.w(TAG, "preview render failed", e)
                    Outcome.Failed
                }
                if (mine != generation) return@launch
                val ms = (System.nanoTime() - started) / 1e6
                val per = built.count.coerceAtLeast(1)
                Log.i(TAG, "render %s frames=%d of %d size=%dx%d bytes=%d avg=%d budget=%d ms=%.0f perFrame=%.1f still=%.1f encode=%.1f".format(
                    outcome, built.count, range.count(), width, height, built.bytes, built.bytes / per, budget, ms, ms / per, stillNs / 1e6 / per, encodeNs / 1e6 / per))
                finish(app, next.copy(resources = resources), built, width, height, outcome, scene.lastFrame, scene.fps)
            } finally {
                if (mine == generation && phase == Phase.Rendering) phase = Phase.Empty
            }
        }
    }

    private class Encoder(private val width: Int, private val height: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val out = ByteArrayOutputStream(width * height / 4)

        fun encode(pixels: ByteArray, cell: Float): ByteArray? {
            flattenOnChecker(pixels, width, height, cell)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            out.reset()
            return if (bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) out.toByteArray() else null
        }
    }

    @Volatile private var stillNs = 0L
    @Volatile private var encodeNs = 0L

    private suspend fun renderFrames(engine: EngineThread, scene: Scene, range: IntRange, width: Int, height: Int, cell: Float, built: FrameStore,
                                     ready: (Long) -> Unit): Outcome =
        engine.exportSession(scene.toXml(), Smoothing(), scene.fps) {
            ready(engine.mediaRevision.value)
            stillNs = 0L
            encodeNs = 0L
            val encoders = arrayOf(Encoder(width, height), Encoder(width, height))
            try {
                coroutineScope {
                    var pending: Deferred<ByteArray?>? = null
                    suspend fun keep(): Outcome? {
                        val previous = pending ?: return null
                        pending = null
                        val bytes = previous.await() ?: return Outcome.Failed
                        if (!built.add(bytes)) return Outcome.Full
                        progress = built.count.toFloat() / range.count()
                        return null
                    }
                    for ((index, frame) in range.withIndex()) {
                        if (stopRequested) return@coroutineScope keep() ?: Outcome.Stopped
                        if (!heapRoom(width.toLong() * height * 12)) return@coroutineScope keep() ?: Outcome.Full
                        val started = System.nanoTime()
                        val pixels = still(scene.frameStart(frame), width, height)
                        stillNs += System.nanoTime() - started
                        keep()?.let { return@coroutineScope it }
                        if (pixels == null) return@coroutineScope Outcome.Failed
                        val encoder = encoders[index and 1]
                        pending = async(Dispatchers.Default) {
                            val began = System.nanoTime()
                            encoder.encode(pixels, cell).also { encodeNs += System.nanoTime() - began }
                        }
                    }
                    keep() ?: Outcome.Done
                }
            } finally {
                encoders.forEach { it.bitmap.recycle() }
            }
        }

    private fun heapRoom(needed: Long): Boolean {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) > needed + 24 * MIB
    }

    private fun finish(context: Context, next: CacheKey, built: FrameStore, width: Int, height: Int, outcome: Outcome, lastFrame: Int, fps: Int) {
        if (outcome == Outcome.NoMemory) {
            clear()
            toast(context, context.getString(R.string.preview_cache_no_memory))
            return
        }
        if (built.count == 0) {
            phase = Phase.Empty
            if (outcome == Outcome.Failed) toast(context, context.getString(R.string.preview_cache_failed))
            else if (outcome == Outcome.Full) toast(context, context.getString(R.string.preview_cache_no_memory))
            return
        }
        store = built
        key = next
        player = FramePlayer(built, width, height)
        phase = Phase.Ready
        progress = 1f
        when {
            outcome == Outcome.Failed -> toast(context, context.getString(R.string.preview_cache_failed))
            outcome != Outcome.Stopped && (built.first > 0 || built.last < lastFrame) ->
                toast(context, context.getString(R.string.preview_cache_partial, timecode(built.first, fps), timecode(built.last + 1, fps)))
        }
    }

    fun rangeLabel(context: Context, fps: Int): String =
        context.getString(R.string.preview_cache_ready_range, timecode(first, fps), timecode(last + 1, fps))

    private fun timecode(frame: Int, fps: Int): String = app.absentmotion.ui.editor.TimelineMath.timecode(frame, fps)

    fun cancel() {
        if (phase == Phase.Rendering) stopRequested = true
    }

    fun clear() {
        generation++
        stopRequested = false
        job?.cancel()
        job = null
        store = null
        key = null
        player?.close()
        player = null
        phase = Phase.Empty
        progress = 0f
    }

    fun trim(context: Context, level: Int) {
        val critical = level == RUNNING_LOW || level == RUNNING_CRITICAL
        if (!critical && level < BACKGROUND) {
            if (level >= UI_HIDDEN) player?.drop()
            return
        }
        if (phase == Phase.Empty) return
        clear()
        Log.i(TAG, "cache freed at trim level $level")
        if (critical) toast(context, context.getString(R.string.preview_cache_trimmed))
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private const val RUNNING_LOW = 10
    private const val RUNNING_CRITICAL = 15
    private const val UI_HIDDEN = 20
    private const val BACKGROUND = 40
}
