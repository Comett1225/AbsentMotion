package app.absentmotion.export

import android.hardware.HardwareBuffer
import android.view.Surface

internal class EncoderCopy(surface: Surface) : AutoCloseable {
    private var handle = nativeOpen(surface).also { check(it != 0L) { "encoder copy set-up failed" } }

    fun copy(buffer: HardwareBuffer, presentNs: Long): Boolean = nativeCopy(handle, buffer, presentNs)

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private companion object {
        @JvmStatic external fun nativeOpen(surface: Surface): Long
        @JvmStatic external fun nativeCopy(handle: Long, buffer: HardwareBuffer, presentNs: Long): Boolean
        @JvmStatic external fun nativeClose(handle: Long)
    }
}
