package app.absentmotion

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import app.absentmotion.editor.EditorState
import app.absentmotion.engine.RenderStats
import kotlinx.coroutines.delay
import org.json.JSONObject

internal suspend fun runBench(activity: Activity, state: EditorState, seconds: Int) {
    val thread = HandlerThread("am-bench").apply { start() }
    val kinds = mapOf("ui" to FrameMetrics.TOTAL_DURATION, "uiInput" to FrameMetrics.INPUT_HANDLING_DURATION,
        "uiAnimation" to FrameMetrics.ANIMATION_DURATION, "uiLayout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
        "uiDraw" to FrameMetrics.DRAW_DURATION, "uiSync" to FrameMetrics.SYNC_DURATION, "uiGpu" to FrameMetrics.GPU_DURATION)
    val ui = kinds.keys.associateWith { ArrayList<Double>() }
    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        synchronized(ui) { for ((name, kind) in kinds) ui.getValue(name) += metrics.getMetric(kind) / 1e6 }
    }
    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    suspend fun phase(name: String, run: suspend () -> Int) {
        RenderStats.take()
        synchronized(ui) { ui.values.forEach { it.clear() } }
        RenderStats.recording = true
        val started = System.nanoTime()
        val advanced = run()
        val elapsed = (System.nanoTime() - started) / 1e9
        RenderStats.recording = false
        val (renders, distinct) = RenderStats.take()
        val result = JSONObject().put("phase", name).put("seconds", elapsed).put("advanced", advanced)
            .put("renders", renders.size).put("distinct", distinct).put("previewFps", distinct / elapsed)
            .put("renderP50", renders.percentile(50)).put("renderP95", renders.percentile(95)).put("renderMax", renders.maxOrNull() ?: 0.0)
        synchronized(ui) {
            val frames = ui.getValue("ui")
            result.put("uiFrames", frames.size).put("uiSlow", frames.count { it > 1000.0 / 60 })
            for ((kind, values) in ui) result.put(kind + "P50", values.percentile(50)).put(kind + "P95", values.percentile(95))
        }
        Log.i("AMBench", result.toString())
    }
    try {
        delay(1500)
        phase("play") {
            val start = state.frame
            state.playing = true
            delay(seconds * 1000L)
            state.playing = false
            state.frame - start
        }
        delay(1000)
        phase("scrub") {
            val steps = seconds * 60
            val last = state.scene.lastFrame.coerceAtLeast(1)
            for (i in 1..steps) {
                state.frame = (i * 2) % last
                delay(16)
            }
            steps
        }
    } finally {
        RenderStats.recording = false
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }
    Log.i("AMBench", "done")
}

private fun List<Double>.percentile(p: Int): Double = if (isEmpty()) 0.0 else sorted()[((size - 1) * p / 100.0).toInt()]
