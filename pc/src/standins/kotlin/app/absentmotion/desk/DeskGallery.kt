package app.absentmotion.desk

import android.database.Cursor
import android.database.DeskCursor
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import android.content.mime

object DeskGallery {
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "svg", "tif", "tiff")
    private val videos = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi")
    private val ids = ConcurrentHashMap<Long, File>()
    private val durations = ConcurrentHashMap<String, Long>()

    private fun scan(video: Boolean): List<File> = DeskLibrary.files(if (video) videos else images)

    fun idOf(file: File): Long = (file.absolutePath.hashCode().toLong() and 0x7fffffffL).also { ids[it] = file }

    fun fileOf(id: Long): File? = ids[id]

    private fun duration(file: File): Long = durations.getOrPut("${file.path}:${file.lastModified()}") { DeskMedia.probe(file)["duration"]?.toLongOrNull() ?: 0L }

    fun query(uri: Uri, projection: Array<String>?): Cursor? {
        val text = uri.toString()
        val video = text.startsWith("content://media/external/video")
        if (!video && !text.startsWith("content://media/external/images")) return null
        val columns = projection ?: arrayOf("_id", "_display_name", "bucket_id", "bucket_display_name", "date_added", "date_modified", "mime_type")
        val files = scan(video).sortedByDescending { it.lastModified() }
        val rows = files.map { f ->
            Array<Any?>(columns.size) { i ->
                when (columns[i]) {
                    "_id" -> idOf(f)
                    "_display_name" -> f.name
                    "bucket_id" -> f.parentFile.absolutePath.hashCode().toString()
                    "bucket_display_name" -> f.parentFile?.name.orEmpty()
                    "date_added", "date_modified" -> f.lastModified() / 1000
                    "mime_type" -> android.content.Desk.mime(f.name)
                    "duration" -> if (video) duration(f) else 0L
                    "_size" -> f.length()
                    "_data" -> f.absolutePath
                    else -> null
                }
            }
        }
        return DeskCursor(columns, rows)
    }
}
