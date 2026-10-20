package app.absentmotion.media

import app.absentmotion.model.Pt
import java.io.Closeable

internal class TrackResult(val points: List<Pt>, val lost: Boolean)

internal interface PointTracker : Closeable {
    fun track(luma: LumaFrame): TrackResult
}

internal class MotionTracker(start: List<Pt>) : PointTracker {
    private val count = start.size
    private var handle = nativeCreate(DoubleArray(count * 2) { (if (it % 2 == 0) start[it / 2].x else start[it / 2].y).toDouble() }, PATCH_RADIUS, SEARCH_RADIUS)
    private val out = DoubleArray(count * 3)

    override fun track(luma: LumaFrame): TrackResult {
        check(handle != 0L) { "The tracker is closed" }
        val found = nativeTrack(handle, luma.pixels, luma.width, luma.height, out)
        return TrackResult(List(count) { Pt(out[3 * it].toFloat(), out[3 * it + 1].toFloat()) }, !found)
    }

    override fun close() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    companion object {
        const val PATCH_RADIUS = 16
        const val SEARCH_RADIUS = 64

        init { runCatching { System.loadLibrary("amengine") } }

        @JvmStatic private external fun nativeCreate(points: DoubleArray, patchRadius: Int, searchRadius: Int): Long
        @JvmStatic private external fun nativeTrack(handle: Long, luma: ByteArray, width: Int, height: Int, out: DoubleArray): Boolean
        @JvmStatic private external fun nativeRelease(handle: Long)
    }
}
