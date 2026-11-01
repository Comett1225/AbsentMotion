package android.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import android.content.mime
import java.io.File

open class MediaMetadataRetriever : AutoCloseable {
    private var file: File? = null
    private val probe: Map<String, String> by lazy { file?.let { DeskMedia.probe(it) }.orEmpty() }

    fun setDataSource(context: Context?, uri: Uri) { file = uri.toFile() }
    fun setDataSource(path: String) { file = File(path) }
    fun setDataSource(fd: java.io.FileDescriptor) {}

    fun extractMetadata(key: Int): String? = when (key) {
        METADATA_KEY_DURATION -> probe["duration"]
        METADATA_KEY_VIDEO_WIDTH -> probe["width"]
        METADATA_KEY_VIDEO_HEIGHT -> probe["height"]
        METADATA_KEY_VIDEO_ROTATION -> probe["rotation"] ?: "0"
        METADATA_KEY_HAS_VIDEO -> if (probe["width"] != null) "yes" else null
        METADATA_KEY_HAS_AUDIO -> if (probe["audio"] == "1") "yes" else null
        METADATA_KEY_MIMETYPE -> file?.name?.let { android.content.Desk.mime(it) }
        METADATA_KEY_CAPTURE_FRAMERATE, METADATA_KEY_VIDEO_FRAME_COUNT -> null
        METADATA_KEY_TITLE -> probe["title"]
        METADATA_KEY_ARTIST -> probe["artist"]
        METADATA_KEY_ALBUM -> probe["album"]
        else -> null
    }

    fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? = getScaledFrameAtTime(timeUs, option, 4096, 4096)
    fun getFrameAtTime(timeUs: Long): Bitmap? = getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)
    fun getFrameAtTime(): Bitmap? = getFrameAtTime(0)
    fun getScaledFrameAtTime(timeUs: Long, option: Int, width: Int, height: Int): Bitmap? =
        file?.let { DeskMedia.frame(it, timeUs / 1000, maxOf(width, height)) }?.let { Bitmap(it) }
    val embeddedPicture: ByteArray? get() = null
    fun release() {}
    override fun close() {}

    companion object {
        const val OPTION_PREVIOUS_SYNC = 0; const val OPTION_NEXT_SYNC = 1; const val OPTION_CLOSEST_SYNC = 2; const val OPTION_CLOSEST = 3
        const val METADATA_KEY_ALBUM = 1; const val METADATA_KEY_ARTIST = 2; const val METADATA_KEY_TITLE = 7; const val METADATA_KEY_MIMETYPE = 12
        const val METADATA_KEY_DURATION = 9; const val METADATA_KEY_HAS_AUDIO = 16; const val METADATA_KEY_HAS_VIDEO = 17
        const val METADATA_KEY_VIDEO_WIDTH = 18; const val METADATA_KEY_VIDEO_HEIGHT = 19; const val METADATA_KEY_VIDEO_ROTATION = 24
        const val METADATA_KEY_CAPTURE_FRAMERATE = 25; const val METADATA_KEY_VIDEO_FRAME_COUNT = 32
    }
}
