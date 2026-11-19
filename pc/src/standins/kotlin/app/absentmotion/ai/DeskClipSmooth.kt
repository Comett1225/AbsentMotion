package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.Smoothing
import app.absentmotion.export.frameCount
import app.absentmotion.export.frameTimeMs
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import app.absentmotion.media.FrameSinkSpec
import app.absentmotion.media.MediaEngine
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.fpsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.util.Locale

internal suspend fun desktopClipSmooth(context: Context, layer: Layer, media: MediaDeclaration, projectFps: Int, smoothing: Smoothing,
                                       providedEngine: EngineThread? = null, progress: AiProgress): AiMedia {
    val fill = checkNotNull(layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
    val plan = checkNotNull(smoothPlan(layer, media, projectFps, smoothing)) { AppText.get(R.string.smooth_nothing) }
    check(plan.seconds <= MAX_MATTE_SECONDS) { AppText.get(R.string.ai_clip_too_long, (MAX_MATTE_SECONDS / 60).toString()) }
    check(plan.frames <= MAX_SMOOTH_FRAMES) { AppText.get(R.string.smooth_too_many_frames, MAX_SMOOTH_FRAMES.toString()) }
    check(plan.width.toLong() * plan.height <= 16_000_000L) { AppText.get(R.string.ai_unable_to_process) }
    val input = Uri.parse(openableUri(fill.uri)).toFile() ?: error(AppText.get(R.string.ai_unable_to_process))
    val start = String.format(Locale.US, "%.6f", plan.fromMs / 1000.0)
    val span = String.format(Locale.US, "%.6f", plan.seconds)
    val file = aiFile(context, "mp4")
    check(file.parentFile!!.usableSpace >= plan.bytes * 2) { AppText.get(R.string.ai_not_enough_storage, (plan.bytes * 2 / 1_000_000).toString()) }
    val scene = smoothScene(fill, media, plan)
    val frames = minOf(plan.frames, frameCount(scene, plan.fps))
    val engine = providedEngine ?: EngineThread(0xff000000.toInt(), context)
    var success = false
    try {
        progress(0f, AppText.get(R.string.ai_preparing))
        val sink = MediaEngine.current.frameSink(file, FrameSinkSpec(plan.width, plan.height, "rgba", "${plan.fps}", null, input, start, span, span))
        try {
            val output = sink.output.buffered(1 shl 20)
            engine.exportSession(scene.toXml(), smoothing.copy(blurScale = plan.blurScale), plan.fps) {
                for (index in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val pixels = checkNotNull(still(frameTimeMs(scene, plan.fps, index), plan.width, plan.height)) { AppText.get(R.string.ai_unable_to_process) }
                    output.write(pixels)
                    progress(0.98f * (index + 1) / frames, AppText.get(R.string.smooth_frame, (index + 1).toString(), frames.toString()))
                }
            }
            output.flush()
            check(runInterruptible(Dispatchers.IO) { sink.finish() }) { AppText.get(R.string.ai_unable_to_process) }
        } finally { sink.close() }
        progress(0.99f, AppText.get(R.string.smooth_saving))
        success = true
        return aiMedia(context, file, "video/mp4", mapOf("width" to "${plan.width}", "height" to "${plan.height}", "duration" to "${(plan.seconds * 1_000_000).toLong()}",
            "fps" to fpsText(plan.fps.toFloat()), "orientation" to "${media.orientation}"))
    } finally {
        if (providedEngine == null) engine.close()
        if (!success) file.delete()
    }
}
