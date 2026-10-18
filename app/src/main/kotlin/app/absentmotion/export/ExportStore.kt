package app.absentmotion.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

class MediaStoreExport(context: Context) : ExportStore {
    private val resolver = context.applicationContext.contentResolver

    override fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val (collection, root) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOCUMENTS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/$AppFolder" + (folder?.let { "/$it" } ?: ""))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Cannot create $name in $root")
        try {
            (resolver.openOutputStream(uri) ?: error("Cannot write $name")).use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    override fun delete(uri: Uri) { resolver.delete(uri, null, null) }
}

class FolderExport(private val dir: File) : ExportStore {
    override fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val file = File(if (folder == null) dir else File(dir, folder), name)
        file.parentFile!!.mkdirs()
        try { file.outputStream().use(write) } catch (t: Throwable) { file.delete(); throw t }
        return Uri.fromFile(file)
    }

    override fun delete(uri: Uri) { uri.path?.let { File(it).delete() } }
}

fun freeSpace(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
