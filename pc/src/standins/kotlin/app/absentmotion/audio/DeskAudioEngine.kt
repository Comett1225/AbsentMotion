package app.absentmotion.audio

import android.content.Context
import app.absentmotion.export.ExportAudio
import app.absentmotion.model.Scene

class AudioEngine(context: Context) {
    @Volatile var quality = Resampler.ULTRA
    @Volatile var log = false
    @Volatile var level = 0f
        private set
    @Volatile var interrupted = false
        private set
    private var clock: Pair<Double, Long>? = null

    init {
        TextBlips.dir = java.io.File(context.cacheDir, "text-blips")
        ExportAudio.mixFor = { scene, rate -> OfflineMix.of(context, scene, Resampler.ULTRA, rate) }
    }

    fun setScene(scene: Scene) {}
    fun play(startMs: Double): Boolean = false
    fun stop() { clock = null }
    fun frameShown(frame: Int, ms: Int) {}
    fun positionMs(): Double? = null
    fun release() {}
    fun close() {}
}
