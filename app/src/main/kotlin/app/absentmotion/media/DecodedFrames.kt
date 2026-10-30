package app.absentmotion.media

import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal interface DecodedFrame : Closeable {
    val timestamp: Long
    val cropRect: Rect
    val hardwareBuffer: HardwareBuffer?
}

internal interface DecodedFrames : Closeable {
    val surface: Surface
    fun poll(timeoutNanos: Long): DecodedFrame?
    fun drain()
}

internal fun decodedFrames(width: Int, height: Int, count: Int): DecodedFrames =
    if (Build.VERSION.SDK_INT >= 28) ImageFrames(width, height, count) else NdkFrames(width, height, count)

@RequiresApi(28)
private class ImageFrame(private val image: Image) : DecodedFrame {
    override val timestamp: Long get() = image.timestamp
    override val cropRect: Rect get() = image.cropRect
    override val hardwareBuffer: HardwareBuffer? get() = image.hardwareBuffer
    override fun close() = image.close()
}

@RequiresApi(28)
private class ImageFrames(width: Int, height: Int, count: Int) : DecodedFrames {
    private val thread = HandlerThread("am-video-images").apply { start() }
    private val images = LinkedBlockingQueue<Image>()
    private val reader = (if (Build.VERSION.SDK_INT >= 29) ImageReader.newInstance(width, height, ImageFormat.PRIVATE, count, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        else ImageReader.newInstance(width, height, ImageFormat.PRIVATE, count)).apply {
        setOnImageAvailableListener({ source ->
            while (true) images.offer((try { source.acquireNextImage() } catch (_: IllegalStateException) { null }) ?: break)
        }, Handler(thread.looper))
    }

    override val surface: Surface get() = reader.surface
    override fun poll(timeoutNanos: Long): DecodedFrame? = images.poll(timeoutNanos, TimeUnit.NANOSECONDS)?.let(::ImageFrame)
    override fun drain() { generateSequence { images.poll() }.forEach { it.close() } }
    override fun close() {
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        thread.quitSafely()
        drain()
    }
}

internal object NdkImages {
    init { System.loadLibrary("amengine") }
    external fun open(width: Int, height: Int, count: Int): Long
    external fun surface(handle: Long): Surface?
    external fun poll(handle: Long, timeoutMs: Long): Long
    external fun drain(handle: Long)
    external fun close(handle: Long)
    external fun timestamp(image: Long): Long
    external fun crop(image: Long, out: IntArray)
    external fun buffer(image: Long): HardwareBuffer?
    external fun release(image: Long)
}

private class NdkFrame(private var image: Long) : DecodedFrame {
    override val timestamp: Long = NdkImages.timestamp(image)
    override val cropRect: Rect = IntArray(4).also { NdkImages.crop(image, it) }.let { Rect(it[0], it[1], it[2], it[3]) }
    override val hardwareBuffer: HardwareBuffer? get() = if (image == 0L) null else NdkImages.buffer(image)
    override fun close() {
        if (image != 0L) NdkImages.release(image)
        image = 0L
    }
}

private class NdkFrames(width: Int, height: Int, count: Int) : DecodedFrames {
    private var handle = NdkImages.open(width, height, count).also { check(it != 0L) { "cannot create a $width x $height image reader" } }
    override val surface: Surface = checkNotNull(NdkImages.surface(handle)) { "the image reader has no surface" }
    override fun poll(timeoutNanos: Long): DecodedFrame? =
        NdkImages.poll(handle, TimeUnit.NANOSECONDS.toMillis(timeoutNanos).coerceAtLeast(0)).takeIf { it != 0L }?.let(::NdkFrame)
    override fun drain() = NdkImages.drain(handle)
    override fun close() {
        if (handle != 0L) NdkImages.close(handle)
        handle = 0L
    }
}
