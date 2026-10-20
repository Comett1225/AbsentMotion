package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
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
import kotlinx.coroutines.isActive
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
        mode.message = AppText.get(R.string.track_motion_playhead_outside)
        return
    }
    if (!continuing) {
        val f0 = runs.first().frames.first()
        val anchor = document.videoToScene(video, f0, width, height)?.map(mode.points.first())
        if (anchor == null) {
            mode.message = AppText.get(R.string.track_motion_no_area)
            return
        }
        val chosen = mode.target?.let(document::layer)
        mode.motion.clear()
        mode.motion[f0] = Sim()
        mode.apply = TrackApply(
            chosen?.id ?: Layer.newId(),
            chosen?.let { document.place(it, document.frameStart(f0)) } ?: Place(anchor.x, anchor.y, 0f, 0f, 1f, 1f, 0f),
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
        var partial: (() -> Map<Int, Sim>)? = null
        try {
            withContext(Dispatchers.IO) {
                FrameReader(context, uri).use { reader ->
                    for ((index, run) in runs.withIndex()) {
                        var shown = 0L
                        val pts = document.framePts(video, run.frames, reader.timestamps)
                        val found = HashMap<Long, List<Pt>>()
                        partial = { document.motionOf(video, trackedSteps(run.frames, pts, found).steps, width, height, run.base) }
                        val outcome = trackFrames(
                            run.frames, pts, reader.timestamps, run.points, width, height,
                            read = { batch, deliver -> reader.read(batch) { p, yuv -> deliver(p, yuv.luma(trackFactor(yuv.width, yuv.height))) } },
                            tracker = { MotionTracker(it) }, found = found,
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
                        partial = null
                        val at = outcome.lostAt ?: continue
                        val base = outcome.steps.lastOrNull()?.let { mode.motion[it.frame] } ?: run.base
                        mode.pending = listOf(TrackRun(run.frames.dropWhile { it != at }, emptyList(), base)) + runs.drop(index + 1)
                        withContext(Dispatchers.Main) {
                            mode.points.clear()
                            mode.points += outcome.lostPoints
                            frame = at
                            mode.lostFrame = at
                            mode.message = if (outcome.steps.isEmpty()) AppText.get(R.string.track_motion_too_little_detail)
                                else AppText.get(R.string.track_motion_lost_at_frame, (at + 1).toString())
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
                mode.message = AppText.get(R.string.track_motion_done, mode.motion.size.toString(), scene.layer(apply.targetId)?.label?.ifEmpty { null } ?: AppText.get(R.string.track_motion_target_layer))
            }
        } catch (_: CancellationException) {
            partial?.takeIf { scope.isActive }?.invoke()?.takeIf { it.size > 1 }?.let { mode.motion.putAll(it); writeTrack(mode, video, apply) }
            mode.message = AppText.get(R.string.track_motion_cancelled)
        } catch (_: LinkageError) {
            mode.message = AppText.get(R.string.track_motion_unavailable)
        } catch (e: Exception) {
            mode.message = e.message ?: AppText.get(R.string.track_motion_failed)
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
            val name = AppText.get(R.string.model_layer_track)
            val count = next.layers.count { it.kind == "nullobj" && it.label.startsWith("$name ") } + 1
            next = next.add(trackNull(apply.targetId, video, Pt(apply.origin.x, apply.origin.y), "$name $count"))
        }
        next.withTrack(apply, mode.motion)
    }
}

private const val LIVE_NS = 80_000_000L
