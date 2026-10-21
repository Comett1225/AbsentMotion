package app.absentmotion.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun mediaDeclaration(context: Context, uri: Uri): MediaDeclaration? =
    if (context.contentResolver.getType(uri)?.startsWith("video/") == true) videoDeclaration(context, uri) else imageDeclaration(context, uri)

suspend fun videoDeclaration(context: Context, uri: Uri): MediaDeclaration? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        var name = uri.lastPathSegment ?: ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val retriever = MediaMetadataRetriever()
        var rotation = 0
        var duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        try {
            retriever.setDataSource(context, uri)
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (duration <= 0) duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        } finally { retriever.release() }
        val fps = (if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() else null)?.takeIf { it > 0 }
            ?: runCatching { estimatedFps(VideoSources.timestamps(context, uri.toString())) }.getOrNull()
        MediaDeclaration(XmlNode("media", listOfNotNull("uri" to uri.toString(), "filename" to name, "title" to name,
            "type" to (context.contentResolver.getType(uri) ?: "video/mp4"), "size" to "$size", "orientation" to "$rotation",
            "duration" to "$duration",
            fps?.let { "fps" to fpsText(it) }, "infoUpdated" to "${System.currentTimeMillis()}",
            "width" to "${format.getInteger(MediaFormat.KEY_WIDTH)}", "height" to "${format.getInteger(MediaFormat.KEY_HEIGHT)}").toMap(LinkedHashMap())))
    } catch (_: Exception) { null } finally { extractor.release() }
}
