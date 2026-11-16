package app.absentmotion.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import app.absentmotion.platform.scaledFrame
import java.io.IOException
import java.nio.ByteBuffer

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
    fun softFrames(context: Context, uri: String): DesktopFrameInput?
    fun softFrame(yuv: Yuv, pts: Long): DecodedFrame
}

internal object MediaBackend {
    @Volatile var ffampReaders: FfampReaders? = null

    fun ffamp(context: Context): FfampReaders? = ffampReaders?.takeIf { MediaReader.ffamp(context) }
}

internal inline fun <T : Any> ffampOrSystem(ffamp: FfampReaders?, fellBack: (Throwable) -> Unit, open: (FfampReaders) -> T, system: () -> T): T {
    if (ffamp != null) {
        try { return open(ffamp) } catch (e: Exception) { fellBack(e) } catch (e: LinkageError) { fellBack(e) }
    }
    return system()
}

private fun fellBack(uri: Uri): (Throwable) -> Unit = { e -> Log.w("AMMedia", "FFamp cannot read $uri, the system reader opens it: $e") }

internal fun Demuxer.hasTrack(kind: String): Boolean = (0 until trackCount).any { track ->
    getTrackFormat(track).getString(MediaFormat.KEY_MIME).let { it != null && it.startsWith(kind) && "/x-ffamp-" !in it }
}

internal fun openDemuxer(context: Context, uri: Uri, kind: String): Demuxer = ffampOrSystem(MediaBackend.ffamp(context), fellBack(uri), { ffamp ->
    ffamp.demuxer(context, uri).also { if (!it.hasTrack(kind)) { it.release(); throw IOException("no $kind track") } }
}) {
    val extractor = MediaExtractor()
    try { extractor.setDataSource(context, uri, null) } catch (e: Exception) { extractor.release(); throw e }
    SystemDemuxer(extractor)
}

internal fun openGrabber(context: Context, uri: Uri): FrameGrabber = ffampOrSystem(MediaBackend.ffamp(context), fellBack(uri), { it.grabber(context, uri) }) {
    val retriever = MediaMetadataRetriever()
    try { retriever.setDataSource(context, uri) } catch (e: Exception) { retriever.release(); throw e }
    SystemGrabber(retriever)
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
