package app.absentmotion.editor

import android.content.Context
import app.absentmotion.media.FrameReader
import app.absentmotion.media.MotionTracker
import app.absentmotion.media.luma
import app.absentmotion.media.trackFactor
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun trackStartPoints(width: Int, height: Int, count: Int): List<Pt> =
    if (count < 2) listOf(Pt(width / 2f, height / 2f)) else listOf(Pt(width * 0.35f, height / 2f), Pt(width * 0.65f, height / 2f))

val Layer.canTrack: Boolean get() = mediaFill?.video == true

fun EditorState.startTracking() {
    val layer = selected?.takeIf { it.canTrack } ?: return
    val media = scene.media(layer.mediaFill!!.uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return
    if (!layer.activeAt(timeMs)) frame = scene.firstShownFrame(layer.startTime)
    selectedId = layer.id
    playing = false
    panel = EditorPanel.Tools
    drawing = TrackMode(layer.id).apply { points += trackStartPoints(media.width, media.height, 1) }
}

fun EditorState.setTrackPointCount(mode: TrackMode, count: Int) {
    if (mode.running || mode.points.size == count) return
    val media = scene.layer(mode.layerId)?.mediaFill?.uri?.let(scene::media) ?: return
    mode.points.clear()
    mode.points += trackStartPoints(media.width, media.height, count)
    mode.pending = emptyList()
    mode.lostFrame = null
}

fun EditorState.runTracking(context: Context, mode: TrackMode, scope: CoroutineScope, resume: Boolean) {
    if (mode.running) return
    val document = scene
    val video = document.layer(mode.layerId) ?: return
    val uri = video.mediaFill?.uri ?: return
    val media = document.media(uri) ?: return
    val width = media.width
    val height = media.height
    val continuing = resume && mode.pending.isNotEmpty() && mode.apply != null && mode.motion.size > 1
    val runs = if (continuing) {
        listOf(TrackRun(mode.pending.first().frames, mode.points.toList(), mode.pending.first().base)) + mode.pending.drop(1)
    } else {
        document.trackRuns(video, frame, mode.range).map { TrackRun(it, mode.points.toList(), Sim()) }
    }
    if (runs.isEmpty() || width <= 0 || height <= 0) {
        mode.message = "Move the playhead inside the video clip first."
        return
    }
    if (!continuing) {
        val f0 = runs.first().frames.first()
        val anchor = document.videoToScene(video, f0, width, height)?.map(mode.points.first())
        if (anchor == null) {
            mode.message = "This layer has no area to track."
            return
        }
        val chosen = mode.target?.let(document::layer)
        mode.motion.clear()
        mode.motion[f0] = Sim()
        mode.apply = TrackApply(
            chosen?.id ?: Layer.newId(), f0,
            chosen?.let { document.place(it, document.frameTime(f0)) } ?: Place(anchor.x, anchor.y, 0f, 0f, 1f, 1f, 0f),
            mode.points.size > 1,
        )
    }
    val apply = mode.apply ?: return
    mode.pending = emptyList()
    mode.lostFrame = null
    mode.running = true
    mode.progress = 0f
    mode.message = ""
    mode.job = scope.launch {
        var lost = false
        var last: TrackStep? = null
        try {
            withContext(Dispatchers.IO) {
                FrameReader(context, uri).use { reader ->
                    for ((index, run) in runs.withIndex()) {
                        var shown = 0L
                        val outcome = trackFrames(
                            run.frames, document.framePts(video, run.frames, reader.timestamps), reader.timestamps, run.points, width, height,
                            read = { batch, deliver -> reader.read(batch) { p, yuv -> deliver(p, yuv.luma(trackFactor(yuv.width, yuv.height))) } },
                            tracker = { MotionTracker(it) },
                        ) { step, fraction ->
                            val now = System.nanoTime()
                            if (now - shown > LIVE_NS) {
                                shown = now
                                withContext(Dispatchers.Main) {
                                    mode.live = step.points
                                    frame = step.frame
                                    mode.progress = (index + fraction) / runs.size
                                }
                            }
                        }
                        val motion = document.motionOf(video, outcome.steps, width, height, run.base)
                        last = outcome.steps.lastOrNull() ?: last
                        withContext(Dispatchers.Main) {
                            mode.motion.putAll(motion)
                            if (motion.size > 1) writeTrack(mode, video, apply)
                        }
                        val at = outcome.lostAt ?: continue
                        val base = outcome.steps.lastOrNull()?.let { mode.motion[it.frame] } ?: run.base
                        mode.pending = listOf(TrackRun(run.frames.dropWhile { it != at }, emptyList(), base)) + runs.drop(index + 1)
                        withContext(Dispatchers.Main) {
                            mode.points.clear()
                            mode.points += outcome.lostPoints
                            frame = at
                            mode.lostFrame = at
                            mode.message = if (outcome.steps.isEmpty()) "This spot has too little detail to follow. Move the point onto a sharp corner or texture and tap Continue."
                                else "Tracking was lost at frame ${at + 1}. Move the point back onto the same feature and tap Continue."
                        }
                        lost = true
                        break
                    }
                }
            }
            if (!lost) {
                last?.let { step ->
                    mode.points.clear()
                    mode.points += step.points
                    frame = step.frame
                }
                mode.message = "Tracked ${mode.motion.size} frames. The keyframes are on ${scene.layer(apply.targetId)?.label?.ifEmpty { null } ?: "the target layer"}."
            }
        } catch (_: CancellationException) {
            mode.message = "Tracking cancelled. Frames already tracked keep their keyframes."
        } catch (_: LinkageError) {
            mode.message = "Motion tracking is unavailable in this build."
        } catch (e: Exception) {
            mode.message = e.message ?: "This video could not be tracked."
        } finally {
            mode.running = false
            mode.live = null
        }
    }
}

private fun EditorState.writeTrack(mode: TrackMode, video: Layer, apply: TrackApply) {
    edit { s ->
        if (s.layer(video.id) == null) return@edit s
        var next = s
        if (next.layer(apply.targetId) == null) {
            if (mode.target != null) return@edit s
            val count = next.layers.count { it.kind == "nullobj" && it.label.startsWith("Track ") } + 1
            next = next.add(trackNull(apply.targetId, video, Pt(apply.origin.x, apply.origin.y), "Track $count"))
        }
        next.withTrack(apply, mode.motion)
    }
}

private const val LIVE_NS = 80_000_000L
