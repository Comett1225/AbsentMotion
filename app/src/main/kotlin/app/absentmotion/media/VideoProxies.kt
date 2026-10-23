package app.absentmotion.media

import app.absentmotion.platform.numberOf
import app.absentmotion.platform.intOr
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object ProxyRules {
    const val MAX_LONG_SIDE = 1920
    const val MAX_SHORT_SIDE = 1080
    const val MAX_SYNC_GAP_US = 2_000_000L
    const val SHORT_SIDE = 540
    const val MAX_TRIES = 3
    const val FRAMES_DIFFER = "frames differ"

    fun failures(record: String?): Int = when (record) {
        null -> 0
        FRAMES_DIFFER -> Int.MAX_VALUE
        else -> record.toIntOrNull() ?: 1
    }

    fun needed(width: Int, height: Int, syncGapUs: Long, hdr: Boolean): Boolean =
        !hdr && width > 0 && height > 0 && (max(width, height) > MAX_LONG_SIDE || min(width, height) > MAX_SHORT_SIDE || syncGapUs > MAX_SYNC_GAP_US)

    fun size(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1.0, SHORT_SIDE.toDouble() / min(width, height))
        fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
        return even(width * scale) to even(height * scale)
    }

    fun bitrate(width: Int, height: Int, fps: Int): Int = (width.toLong() * height * fps.coerceIn(1, 120) / 5).coerceIn(500_000, 8_000_000).toInt()

    fun key(uri: String, version: String?): String? = version?.let {
        MessageDigest.getInstance("SHA-256").digest("$uri\n$it".toByteArray()).joinToString("") { b -> "%02x".format(b) }.take(32)
    }

    fun sameFrames(source: LongArray, proxy: LongArray): Boolean =
        source.size == proxy.size && source.indices.all { abs(source[it] - proxy[it]) <= 1_000 }
}

object VideoProxies {
    const val SETTING = "previewCopies"
    private const val BUDGET_BYTES = 2L shl 30
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "am-proxy").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher())
    private val queued = Collections.synchronizedSet(HashSet<String>())
    private val settled = Collections.synchronizedSet(HashSet<String>())
    private val making = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val made = MutableStateFlow(0)
    @Volatile private var generation = 0
    @Volatile internal var paused = false

    val progress: StateFlow<Map<String, Float>> = making.asStateFlow()
    val revision: StateFlow<Int> = made.asStateFlow()

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(SETTING, true)

    fun dir(context: Context): File = File(context.cacheDir, "proxies")

    fun bytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    internal fun ready(context: Context, uri: String, version: String?): String? {
        if (!enabled(context)) return null
        val file = File(dir(context), (ProxyRules.key(uri, version) ?: return null) + ".mp4")
        return if (file.isFile) Uri.fromFile(file).toString() else null
    }

    internal fun request(context: Context, uri: String) {
        if (uri in settled || paused || !enabled(context) || !queued.add(uri)) return
        val app = context.applicationContext
        val started = generation
        scope.launch {
            try { prepare(app, uri) { started != generation || paused || !enabled(app) } }
            catch (e: Exception) { Log.w("AMProxy", "cannot make a preview copy of $uri", e) }
            finally { queued.remove(uri); making.update { it - uri } }
        }
    }

    internal fun forgetChecks() = settled.clear()

    fun settingChanged() {
        settled.clear()
        made.update { it + 1 }
    }

    fun clear(context: Context) {
        generation++
        settled.clear()
        scope.launch {
            dir(context).listFiles()?.forEach { it.delete() }
            made.update { it + 1 }
        }
    }

    private fun prepare(context: Context, uri: String, cancelled: () -> Boolean) {
        val version = fileVersion(context.contentResolver, Uri.parse(uri))
        val key = ProxyRules.key(uri, version) ?: return
        val folder = dir(context).apply { mkdirs() }
        val done = File(folder, "$key.mp4")
        val failed = File(folder, "$key.failed")
        if (cancelled()) return
        val failures = ProxyRules.failures(failed.takeIf { it.isFile }?.readText())
        if (done.isFile || failures >= ProxyRules.MAX_TRIES) { settled += uri; return }
        val info = sourceInfo(context, uri)
        if (!ProxyRules.needed(info.width, info.height, VideoSources.syncGapUs(context, uri), info.hdr)) { settled += uri; return }
        val part = File(folder, "$key.part")
        Log.i("AMProxy", "making a preview copy of $uri (${info.width}x${info.height})")
        val started = System.nanoTime()
        try {
            making.update { it + (uri to 0f) }
            var shown = 0f
            val finished = transcode(context, uri, part, cancelled) { p ->
                if (p - shown >= 0.01f) { shown = p; making.update { it + (uri to p) } }
            }
            if (!finished) { part.delete(); return }
            if (!ProxyRules.sameFrames(VideoSources.timestamps(context, uri), VideoSources.timestamps(context, Uri.fromFile(part).toString()))) {
                part.delete()
                failed.writeText(ProxyRules.FRAMES_DIFFER)
                settled += uri
                Log.w("AMProxy", "the preview copy of $uri has different frame times; using the original")
                return
            }
            check(part.renameTo(done))
            failed.delete()
            settled += uri
            Log.i("AMProxy", "preview copy of $uri ready in ${(System.nanoTime() - started) / 1_000_000} ms, ${done.length()} bytes")
            trim(folder)
            made.update { it + 1 }
        } catch (e: Exception) {
            part.delete()
            if (!cancelled()) { failed.writeText("${failures + 1}"); settled += uri }
            throw e
        }
    }

    private fun trim(folder: File) {
        folder.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
        val copies = folder.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in copies) { total += f.length(); if (total > BUDGET_BYTES) f.delete() }
    }
}

private class SourceInfo(val width: Int, val height: Int, val hdr: Boolean)

private fun sourceInfo(context: Context, uri: String): SourceInfo {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val format = videoTrack(extractor).second
        val (width, height) = visibleSize(format)
        return SourceInfo(width, height, isHdrTransfer(format.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)))
    } finally { extractor.release() }
}

private fun videoTrack(extractor: MediaExtractor): Pair<Int, MediaFormat> =
    (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }.first { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }

private fun visibleSize(format: MediaFormat): Pair<Int, Int> {
    val width = format.getInteger(MediaFormat.KEY_WIDTH)
    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
    if (!format.containsKey("crop-right") || !format.containsKey("crop-bottom")) return width to height
    return (format.getInteger("crop-right") - format.intOr("crop-left", 0) + 1) to (format.getInteger("crop-bottom") - format.intOr("crop-top", 0) + 1)
}

private fun transcode(context: Context, uri: String, out: File, cancelled: () -> Boolean, progress: (Float) -> Unit): Boolean {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    var encoder: MediaCodec? = null
    var gl: ProxyGl? = null
    var frames: SurfaceTexture? = null
    var decoderSurface: Surface? = null
    var muxer: MediaMuxer? = null
    val thread = HandlerThread("am-proxy-frames").apply { start() }
    try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val (track, format) = videoTrack(extractor)
        extractor.selectTrack(track)
        val (visibleWidth, visibleHeight) = visibleSize(format)
        val (width, height) = ProxyRules.size(visibleWidth, visibleHeight)
        val fps = (if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.numberOf(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 30f else 30f).roundToInt().coerceIn(1, 120)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val encoderFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, ProxyRules.bitrate(width, height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.25f)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        encoder = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(encoderFormat)
            ?: error("no encoder for a ${width}x$height preview copy"))
        encoder.configureFitting(encoderFormat, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        gl = ProxyGl(encoder.createInputSurface(), width, height)
        val available = Semaphore(0)
        frames = SurfaceTexture(gl.texture).apply { setOnFrameAvailableListener({ available.release() }, Handler(thread.looper)) }
        decoderSurface = Surface(frames)
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        decoder = openDecoder(format, decoderSurface)
        encoder.start()
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        val matrix = FloatArray(16)
        var inputDone = false
        var decodeDone = false
        var muxTrack = -1
        var encoded = false
        fun drain(wait: Boolean): Boolean {
            var quiet = System.nanoTime()
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { muxTrack = muxer.addTrack(encoder.outputFormat); muxer.start(); quiet = System.nanoTime() }
                    index >= 0 -> {
                        quiet = System.nanoTime()
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxTrack >= 0) {
                            muxer.writeSampleData(muxTrack, encoder.getOutputBuffer(index)!!, info)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encoded = true; return true }
                    }
                    else -> if (!wait) return false else {
                        if (cancelled()) return false
                        check(System.nanoTime() - quiet < TimeUnit.SECONDS.toNanos(10)) { "the encoder did not finish the preview copy" }
                    }
                }
            }
        }
        while (!decodeDone) {
            if (cancelled()) return false
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 5_000)
            if (index >= 0) {
                val pts = info.presentationTimeUs
                val shown = info.size > 0
                decodeDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoder.releaseOutputBuffer(index, shown)
                if (shown) {
                    check(available.tryAcquire(2, TimeUnit.SECONDS)) { "the decoder did not deliver frame $pts" }
                    frames.updateTexImage()
                    frames.getTransformMatrix(matrix)
                    gl.draw(matrix, pts * 1000)
                    if (durationUs > 0) progress((pts.toFloat() / durationUs).coerceIn(0f, 0.99f))
                }
                if (decodeDone) encoder.signalEndOfInputStream()
            }
            drain(false)
        }
        if (!encoded && !drain(true)) return false
        muxer.stop()
        return true
    } finally {
        runCatching { decoder?.stop() }; decoder?.release()
        decoderSurface?.release()
        frames?.release()
        gl?.close()
        runCatching { encoder?.stop() }; encoder?.release()
        runCatching { muxer?.release() }
        extractor.release()
        thread.quitSafely()
    }
}

private class ProxyGl(private val surface: Surface, private val width: Int, private val height: Int) : Closeable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    var texture = 0
        private set
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0) }

    init {
        try {
            check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "EGL is unavailable" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val wanted = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no recordable EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "cannot use the EGL surface" }
            program = link(VERTEX, FRAGMENT)
            texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun draw(matrix: FloatArray, timeNs: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val corner = GLES20.glGetAttribLocation(program, "corner")
        GLES20.glEnableVertexAttribArray(corner)
        GLES20.glVertexAttribPointer(corner, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        val dx = 0.25f / width
        val dy = 0.25f / height
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "across"), matrix[0] * dx, matrix[1] * dx)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "down"), matrix[4] * dy, matrix[5] * dy)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, timeNs)
        check(EGL14.eglSwapBuffers(display, window)) { "cannot hand a frame to the encoder" }
    }

    override fun close() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        surface.release()
    }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec2 corner;
uniform mat4 transform;
varying vec2 uv;
void main() {
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
    uv = (transform * vec4(corner, 0.0, 1.0)).xy;
}
"""
        const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES frame;
uniform vec2 across;
uniform vec2 down;
varying vec2 uv;
void main() {
    gl_FragColor = 0.25 * (texture2D(frame, uv + across + down) + texture2D(frame, uv + across - down)
        + texture2D(frame, uv - across + down) + texture2D(frame, uv - across - down));
}
"""
    }
}
