package app.absentmotion.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.scaledFrame
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

internal interface Demuxer {
    val trackCount: Int
    val sampleTime: Long
    val hasSample: Boolean
    val sampleFlags: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
    fun advance(): Boolean
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    fun seekTo(timeUs: Long, mode: Int)
    fun release()
}

internal interface FrameGrabber {
    fun extractMetadata(key: Int): String?
    fun scaledFrame(timeUs: Long, option: Int, width: Int, height: Int): Bitmap?
    fun release()
}

internal interface FfampReaders {
    fun demuxer(context: Context, uri: Uri): Demuxer
    fun grabber(context: Context, uri: Uri): FrameGrabber
    fun softFrames(context: Context, uri: String, rescue: Boolean = false): DesktopFrameInput?
    fun softFrame(yuv: Yuv, pts: Long, standard: Int, fullRange: Boolean): DecodedFrame
    fun reads(context: Context, uri: Uri, kind: String): Boolean
}

internal object MediaBackend {
    @Volatile var ffampReaders: FfampReaders? = null
    val rescue: FfampReaders? get() = ffampReaders?.takeIf { PlatformFeatures.ffamp }
    private val rescued = ConcurrentHashMap<String, Boolean>()

    fun ffamp(context: Context, uri: Uri, kind: String): FfampReaders? = pickFfamp(ffampReaders, { MediaReader.ffamp(context) }, { PlatformFeatures.ffamp }) { readers ->
        val key = fileVersion(context.contentResolver, uri)?.let { "$kind\n$uri\n$it" }
        ffampRescues(rescued, key, { systemReads(context, uri, kind) }) {
            readers.reads(context, uri, kind).also { if (it) Log.i("AMMedia", "the system cannot read $uri, FFamp reads its $kind track") }
        }
    }
}

internal fun pickFfamp(readers: FfampReaders?, chosen: () -> Boolean, allowed: () -> Boolean, rescues: (FfampReaders) -> Boolean): FfampReaders? =
    readers?.takeIf { chosen() || allowed() && rescues(it) }

internal fun ffampRescues(known: MutableMap<String, Boolean>, key: String?, system: () -> Boolean, ffamp: () -> Boolean): Boolean =
    key != null && known.getOrPut(key) { !system() && ffamp() }

internal fun soundFound(systemLists: Boolean?, ffampDecodes: () -> Boolean, ffampLists: () -> Boolean?): Boolean =
    systemLists?.let { it || ffampDecodes() } ?: ffampLists() ?: true

internal fun ffampHasTrack(context: Context, uri: Uri, kind: String): Boolean? = MediaBackend.rescue?.let { readers ->
    runCatching { readers.demuxer(context, uri).run { try { (0 until trackCount).any { getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true } } finally { release() } } }.getOrNull()
}

private fun systemReads(context: Context, uri: Uri, kind: String): Boolean {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }
            ?.let { decoderNames(it).isNotEmpty() } == true
    } catch (_: Exception) { false } finally { extractor.release() }
}

internal inline fun <T : Any> ffampOrSystem(ffamp: FfampReaders?, fellBack: (Throwable) -> Unit, open: (FfampReaders) -> T, system: () -> T): T {
    if (ffamp != null) {
        try { return open(ffamp) } catch (e: Exception) { fellBack(e) } catch (e: LinkageError) { fellBack(e) }
    }
    return system()
}

internal inline fun <T, R : Any> systemOrFfamp(system: () -> T, rescue: (Exception) -> R?, resume: (R) -> T): T =
    try { system() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { resume(rescue(e) ?: throw e) }

private fun fellBack(uri: Uri): (Throwable) -> Unit = { e -> Log.w("AMMedia", "FFamp cannot read $uri, the system reader opens it: $e") }

internal fun Demuxer.hasTrack(kind: String): Boolean = (0 until trackCount).any { track ->
    getTrackFormat(track).getString(MediaFormat.KEY_MIME).let { it != null && it.startsWith(kind) && "/x-ffamp-" !in it }
}

internal fun openDemuxer(context: Context, uri: Uri, kind: String): Demuxer = ffampOrSystem(MediaBackend.ffamp(context, uri, kind), fellBack(uri), { ffamp ->
    ffamp.demuxer(context, uri).also { if (!it.hasTrack(kind)) { it.release(); throw IOException("no $kind track") } }
}) {
    val extractor = MediaExtractor()
    try { extractor.setDataSource(context, uri, null) } catch (e: Exception) { extractor.release(); throw e }
    SystemDemuxer(extractor)
}

internal fun openGrabber(context: Context, uri: Uri): FrameGrabber = ffampOrSystem(MediaBackend.ffamp(context, uri, "video/"), fellBack(uri), { it.grabber(context, uri) }) {
    val retriever = MediaMetadataRetriever()
    try { retriever.setDataSource(context, uri) } catch (e: Exception) { retriever.release(); throw e }
    SystemGrabber(retriever)
}

internal fun firstFrame(context: Context, uri: Uri, size: Int): Bitmap? = openGrabber(context, uri).run {
    try { scaledFrame(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size) } finally { release() }
}

private class SystemDemuxer(private val extractor: MediaExtractor) : Demuxer {
    override val trackCount get() = extractor.trackCount
    override val sampleTime get() = extractor.sampleTime
    override val hasSample get() = if (android.os.Build.VERSION.SDK_INT >= 28) extractor.sampleSize >= 0 else extractor.sampleTime >= 0
    override val sampleFlags get() = extractor.sampleFlags
    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
    override fun selectTrack(index: Int) = extractor.selectTrack(index)
    override fun advance() = extractor.advance()
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = extractor.readSampleData(buffer, offset)
    override fun seekTo(timeUs: Long, mode: Int) = extractor.seekTo(timeUs, mode)
    override fun release() = extractor.release()
}

private class SystemGrabber(private val retriever: MediaMetadataRetriever) : FrameGrabber {
    override fun extractMetadata(key: Int): String? = retriever.extractMetadata(key)
    override fun scaledFrame(timeUs: Long, option: Int, width: Int, height: Int): Bitmap? = retriever.scaledFrame(timeUs, option, width, height)
    override fun release() = retriever.release()
}
