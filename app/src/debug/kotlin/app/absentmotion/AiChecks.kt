package app.absentmotion

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.absentmotion.ai.AiModel
import app.absentmotion.ai.AiNative
import app.absentmotion.ai.AiNet
import app.absentmotion.ai.AiSmooth
import app.absentmotion.ai.model
import app.absentmotion.ai.padding
import app.absentmotion.export.SmoothLevel
import app.absentmotion.ai.aiMarks
import app.absentmotion.ai.withoutAiMark
import androidx.lifecycle.lifecycleScope
import kotlin.math.log10
import kotlin.math.sin

private const val TAG = "AMAi"

internal fun movingFrame(width: Int, height: Int, shift: Float): IntArray = IntArray(width * height) { i ->
    val x = i % width
    val y = i / width
    val left = width * 0.25f + shift
    val inside = x >= left && x < left + width * 0.25f && y >= height * 0.3f && y < height * 0.7f
    fun wave(k: Float) = (127 + 60 * sin(x * k) * sin(y * k * 1.3f)).toInt().coerceIn(0, 255)
    val (r, g, b) = if (inside) Triple(230, 140 + ((x - left).toInt() * 9 % 90), 40) else Triple(wave(0.11f), wave(0.07f), wave(0.05f))
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun psnr(a: IntArray, b: IntArray): Double {
    var sum = 0.0
    for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) {
        val d = ((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)
        sum += d * d
    }
    val mse = sum / (a.size * 3)
    return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
}

internal fun blend(a: IntArray, b: IntArray): IntArray = IntArray(a.size) { i ->
    var out = 0xFF shl 24
    for (shift in intArrayOf(16, 8, 0)) out = out or (((((a[i] shr shift) and 0xFF) + ((b[i] shr shift) and 0xFF) + 1) / 2) shl shift)
    out
}

internal suspend fun checkAi(context: Context, gpu: Boolean) {
    Log.i(TAG, "runtime loaded=${AiNative.loaded} gpu=${AiNative.gpu?.let { "${it.name} vulkan ${it.version} type ${it.type} fp16 ${it.fp16}" }} cores=${if (AiNative.loaded) AiNative.nativeCpuCores() else 0} useGpu=$gpu")
    for (model in listOf(AiModel.Detail, AiModel.Cartoon)) {
        if (!model.installed(context)) { Log.i(TAG, "skip $model: not installed"); continue }
        val blend = if (model == AiModel.Detail && AiModel.Grain.installed(context)) AiModel.Grain else null
        val started = SystemClock.elapsedRealtime()
        AiNet.open(context, model, blend, 0.5f, gpu).use { net ->
            val opened = SystemClock.elapsedRealtime()
            val (w, h) = 64 to 48
            val frame = movingFrame(w, h, 0f)
            val out = net.upscale(frame, w, h, 4)
            val first = SystemClock.elapsedRealtime()
            val again = net.upscale(frame, w, h, 4)
            val second = SystemClock.elapsedRealtime()
            val sharpen = net.upscale(frame, w, h, 1)
            val near = psnr(sharpen, frame)
            val sane = out.contentEquals(again) && out.any { (it and 0xFFFFFF) != 0 }
            Log.i(TAG, "ok=${sane && near > 20} $model blend=${blend != null} gpu=${net.onGpu} open=${opened - started}ms first=${first - opened}ms second=${second - first}ms out=${w * 4}x${h * 4} sharpenPsnr=${"%.1f".format(java.util.Locale.US, near)}")
        }
    }
    for (level in SmoothLevel.entries) {
        if (!AiSmooth.installed(context, level)) { Log.i(TAG, "skip smooth $level: not installed"); continue }
        val started = SystemClock.elapsedRealtime()
        AiSmooth.open(context, level, gpu).use { smooth ->
            val opened = SystemClock.elapsedRealtime()
            val (w, h) = 160 to 96
            val a = movingFrame(w, h, 0f)
            val b = movingFrame(w, h, 16f)
            val truth = movingFrame(w, h, 8f)
            val mid = smooth.between(a, b, w, h, 0.5f)
            val ran = SystemClock.elapsedRealtime()
            val ai = psnr(mid, truth)
            val mixed = psnr(blend(a, b), truth)
            val small = AiNet.open(context, level.model, gpu = gpu).use { net -> psnr(net.interpolate(a, b, w, h, 0.5f, level.padding, w * h / 4), truth) }
            Log.i(TAG, "ok=${ai > mixed + 3 && small > mixed + 3} smooth $level gpu=${smooth.onGpu} open=${opened - started}ms run=${ran - opened}ms psnr ai=${"%.2f".format(java.util.Locale.US, ai)} quarter=${"%.2f".format(java.util.Locale.US, small)} blend=${"%.2f".format(java.util.Locale.US, mixed)}")
        }
    }
    Log.i(TAG, "done")
}

internal suspend fun checkEnhance(activity: MainActivity, path: String, modes: List<String>) {
    val source = java.io.File(path)
    val declared = checkNotNull(app.absentmotion.media.videoDeclaration(activity, android.net.Uri.fromFile(source))) { "cannot read $path" }
    val uri = declared.uri
    for (name in modes) {
        val options = checkNotNull(app.absentmotion.ai.EnhanceOptions.parse(name)) { "unknown mode $name" }
        val xml = """<scene width="640" height="360" fps="30" totalTime="2000"><shape id="1" label="clip" s=".rect" fillType="media" fillVideo="$uri" mediaFillMode="fit" startTime="0" endTime="2000"><transform><location value="320,180,0"/></transform><property name="size" type="vec2" value="640,360"/></shape></scene>"""
        val id = "test-enhance-${name.replace('+', '-')}"
        activity.store.save(id, app.absentmotion.model.Scene.parse(xml).withMedia(declared))
        val state = checkNotNull(activity.openNow(id)) { "cannot open $id" }
        val tools = app.absentmotion.ai.AiTools(activity.applicationContext, state, activity.lifecycleScope)
        state.selectedId = 1
        tools.enhance = options
        val started = SystemClock.elapsedRealtime()
        tools.apply(app.absentmotion.ai.AiAction.Enhance, confirmed = true)
        while (tools.running != null) kotlinx.coroutines.delay(250)
        val ms = SystemClock.elapsedRealtime() - started
        val layer = checkNotNull(state.scene.layer(1))
        val result = layer.node["fillVideo"]
        if (result == uri) { Log.e(TAG, "ok=false enhance $name: ${tools.message}"); continue }
        val media = checkNotNull(state.scene.media(result!!))
        val mark = layer.aiMarks.single()
        val frames = app.absentmotion.media.VideoSources.timestamps(activity, result).size
        val sourceFrames = app.absentmotion.media.VideoSources.timestamps(activity, uri).size
        val tracks = android.media.MediaExtractor().run {
            setDataSource(activity, android.net.Uri.parse(result), null)
            (0 until trackCount).map { getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) }.also { release() }
        }
        val sizeOk = media.width == declared.width * options.mode.scale && media.height == declared.height * options.mode.scale
        activity.engine.awaitMedia()
        runCatching { activity.engine.awaitVideoFrames(state.scene, 500) }
        val still = java.io.File(activity.getExternalFilesDir(null), "enhance-$name.png".replace('+', '-'))
        val previewed = activity.engine.stillPng(state.scene.toXml(), 500, 640, 360, still)
        state.undo()
        val undone = state.scene.layer(1)?.node?.get("fillVideo") == uri && state.scene.layer(1)?.aiMarks?.isEmpty() == true
        state.redo()
        val redone = state.scene.layer(1)?.node?.get("fillVideo") == result
        state.editSelected { it.withoutAiMark(app.absentmotion.ai.AiKind.Enhance) }
        val restored = state.scene.layer(1)?.node?.get("fillVideo") == uri && state.scene.layer(1)?.aiMarks?.isEmpty() == true
        val ok = sizeOk && frames >= sourceFrames && tracks.any { it?.startsWith("audio/") == true } && mark.original == uri && previewed && undone && redone && restored
        Log.i(TAG, "ok=$ok enhance $name ms=$ms ${declared.width}x${declared.height} -> ${media.width}x${media.height} frames $sourceFrames -> $frames tracks=$tracks preview=$previewed undo=$undone redo=$redone restore=$restored")
    }
    Log.i(TAG, "enhance done")
}

internal suspend fun checkSmooth(activity: MainActivity, path: String, levels: List<String>) {
    val declared = checkNotNull(app.absentmotion.media.videoDeclaration(activity, android.net.Uri.fromFile(java.io.File(path)))) { "cannot read $path" }
    val uri = declared.uri
    for (name in levels) {
        val method = if (name.startsWith("flow")) app.absentmotion.export.FrameInterpolation.Flow else app.absentmotion.export.FrameInterpolation.Ai
        val level = SmoothLevel.valueOf(name.removePrefix("flow"))
        val xml = """<scene width="640" height="360" fps="30" totalTime="2000"><shape id="1" label="clip" s=".rect" fillType="media" fillVideo="$uri" mediaFillMode="fit" startTime="0" endTime="2000"><transform><location value="320,180,0"/></transform><property name="size" type="vec2" value="640,360"/></shape></scene>"""
        val id = "test-aismooth-$name"
        activity.store.save(id, app.absentmotion.model.Scene.parse(xml).withMedia(declared))
        val state = checkNotNull(activity.openNow(id)) { "cannot open $id" }
        val tools = app.absentmotion.ai.AiTools(activity.applicationContext, state, activity.lifecycleScope)
        state.selectedId = 1
        tools.smoothing = app.absentmotion.export.Smoothing(method, level)
        val started = SystemClock.elapsedRealtime()
        tools.apply(app.absentmotion.ai.AiAction.Smooth, confirmed = true)
        while (tools.running != null) kotlinx.coroutines.delay(250)
        val ms = SystemClock.elapsedRealtime() - started
        val result = state.scene.layer(1)?.node?.get("fillVideo")
        if (result == null || result == uri) { Log.e(TAG, "ok=false aismooth $name: ${tools.message}"); continue }
        val media = checkNotNull(state.scene.media(result))
        val frames = app.absentmotion.media.VideoSources.timestamps(activity, result).size
        val still = java.io.File(activity.getExternalFilesDir(null), "aismooth-$name.png")
        activity.engine.awaitMedia()
        runCatching { activity.engine.awaitVideoFrames(state.scene, 500) }
        val previewed = activity.engine.stillPng(state.scene.toXml(), 500, 640, 360, still)
        state.undo()
        val undone = state.scene.layer(1)?.node?.get("fillVideo") == uri
        val ok = frames >= 55 && previewed && undone && (media.fps ?: 0f) >= 29f
        Log.i(TAG, "ok=$ok aismooth $name ms=$ms frames=$frames fps=${media.fps} ${media.width}x${media.height} preview=$previewed undo=$undone")
    }
    Log.i(TAG, "aismooth done")
}

internal suspend fun checkVideoCut(activity: MainActivity, path: String, side: Int) {
    val declared = checkNotNull(app.absentmotion.media.videoDeclaration(activity, android.net.Uri.fromFile(java.io.File(path)))) { "cannot read $path" }
    val ms = (declared.duration / 1000).toInt().coerceAtLeast(1000)
    val xml = """<scene width="1920" height="1080" fps="30" totalTime="$ms"><shape id="1" label="clip" s=".rect" fillType="media" fillVideo="${declared.uri}" mediaFillMode="fit" startTime="0" endTime="$ms"><transform><location value="960,540,0"/></transform><property name="size" type="vec2" value="960,540"/></shape></scene>"""
    activity.store.save("test-videocut", app.absentmotion.model.Scene.parse(xml).withMedia(declared))
    val state = checkNotNull(activity.openNow("test-videocut")) { "cannot open test-videocut" }
    val tools = app.absentmotion.ai.AiTools(activity.applicationContext, state, activity.lifecycleScope)
    state.selectedId = 1
    tools.videoSide = side
    val started = SystemClock.elapsedRealtime()
    tools.apply(app.absentmotion.ai.AiAction.Video, confirmed = true)
    while (tools.running != null) kotlinx.coroutines.delay(250)
    val layers = state.scene.layers.size
    Log.i(TAG, "ok=${tools.message.isEmpty()} videocut ${declared.width}x${declared.height} side=$side ms=${SystemClock.elapsedRealtime() - started} layers=$layers message=${tools.message}")
}

internal suspend fun checkDownload(context: Context, name: String, stopAfterMs: Long) {
    val model = AiModel.valueOf(name)
    model.delete(context)
    var reached = 0f
    if (stopAfterMs > 0) kotlinx.coroutines.withTimeoutOrNull(stopAfterMs) { model.download(context) { reached = it } }
    val parts = model.dir(context).listFiles()?.filter { it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
    val started = SystemClock.elapsedRealtime()
    var first = -1f
    model.download(context) { if (first < 0 && it > 0.01f) first = it }
    model.verify(context)
    Log.i(TAG, "ok=${model.installed(context)} download $model stoppedAt=${"%.2f".format(java.util.Locale.US, reached)} kept=$parts resumedFrom=${"%.2f".format(java.util.Locale.US, first)} ms=${SystemClock.elapsedRealtime() - started} bytes=${model.onDisk(context)}")
    Log.i(TAG, "download done")
}
