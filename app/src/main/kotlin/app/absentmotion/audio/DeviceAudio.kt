package app.absentmotion.audio

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceAudio(val uri: Uri, val title: String, val durationMs: Long, val date: Long)

suspend fun deviceAudio(context: Context): List<DeviceAudio> = withContext(Dispatchers.IO) {
    val found = mutableListOf<DeviceAudio>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    try {
        context.contentResolver.query(collection, arrayOf("_id", "title", "_display_name", "duration", "date_added"),
            "duration > 0", null, "date_added DESC, _id DESC")?.use { c ->
            while (c.moveToNext()) found += DeviceAudio(ContentUris.withAppendedId(collection, c.getLong(0)),
                c.getString(1) ?: c.getString(2) ?: "", c.getLong(3), c.getLong(4))
        }
    } catch (_: Exception) { }
    found
}

suspend fun audioDeclaration(context: Context, uri: Uri): MediaDeclaration? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return@withContext null
        var name = uri.lastPathSegment ?: ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        MediaDeclaration(XmlNode("media", linkedMapOf("uri" to uri.toString(), "filename" to name, "title" to name,
            "type" to (context.contentResolver.getType(uri) ?: "audio/mp4"),
            "duration" to "${if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L}",
            "size" to "$size", "infoUpdated" to "${System.currentTimeMillis()}")))
    } catch (_: Exception) { null } finally { extractor.release() }
}
