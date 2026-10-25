package app.absentmotion.export

import android.hardware.HardwareBuffer
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class EncoderCopy(surface: Surface, turn: Boolean) : AutoCloseable {
    private var handle = nativeOpen(surface, turn).also { check(it != 0L) { "encoder copy set-up failed" } }

    fun copy(buffer: HardwareBuffer, presentNs: Long): Boolean = nativeCopy(handle, buffer, presentNs)

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private companion object {
        @JvmStatic external fun nativeOpen(surface: Surface, turn: Boolean): Long
        @JvmStatic external fun nativeCopy(handle: Long, buffer: HardwareBuffer, presentNs: Long): Boolean
        @JvmStatic external fun nativeClose(handle: Long)
    }
}

internal class EncoderFeed(input: Surface, width: Int, height: Int, turn: Boolean = false) : AutoCloseable {
    private val buffers = Array(2) { HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE) }
    private val copy = try { EncoderCopy(input, turn) } catch (t: Throwable) { buffers.forEach { it.close() }; throw t }
    private val copier = Executors.newSingleThreadExecutor { Thread(it, "am-export-copy") }
    private val copies = arrayOfNulls<Future<Boolean>>(2)
    private var sent = 0
    var waitedNs = 0L
        private set

    val next: HardwareBuffer get() = buffers[sent % 2]

    fun ready(): Boolean = done(sent % 2)

    fun send(presentNs: Long) {
        val buffer = next
        copies[sent++ % 2] = copier.submit<Boolean> { copy.copy(buffer, presentNs) }
    }

    fun flush(): Boolean = done(sent % 2) and done((sent + 1) % 2)

    private fun done(slot: Int): Boolean {
        val copying = copies[slot] ?: return true
        copies[slot] = null
        val started = System.nanoTime()
        return runCatching { copying.get() }.getOrDefault(false).also { waitedNs += System.nanoTime() - started }
    }

    override fun close() {
        copies.forEach { runCatching { it?.get() } }
        copier.shutdown()
        copy.close()
        buffers.forEach { it.close() }
    }
}
