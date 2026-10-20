package app.absentmotion.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.File
import java.io.OutputStream

class MediaStoreExport(context: Context) : ExportStore {
    private val resolver = context.applicationContext.contentResolver

    private fun insert(folder: String?, name: String, mime: String): Uri {
        val (collection, root) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOCUMENTS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/$AppFolder" + (folder?.let { "/$it" } ?: ""))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: throw ExportError(txt(R.string.export_error_create, name, root))
    }

    override fun open(folder: String?, name: String, mime: String): OpenExport {
        val uri = insert(folder, name, mime)
        val file = try { resolver.openFileDescriptor(uri, "rw") ?: throw ExportError(txt(R.string.export_error_write, name)) }
            catch (t: Throwable) { resolver.delete(uri, null, null); throw t }
        return OpenExport(uri, file.fileDescriptor, file::close,
            { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) },
            { resolver.delete(uri, null, null) })
    }

    override fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val uri = insert(folder, name, mime)
        try {
            (resolver.openOutputStream(uri) ?: throw ExportError(txt(R.string.export_error_write, name))).use(write)
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
    override fun open(folder: String?, name: String, mime: String): OpenExport {
        val file = File(if (folder == null) dir else File(dir, folder), name)
        file.parentFile!!.mkdirs()
        val access = java.io.RandomAccessFile(file, "rw")
        access.setLength(0)
        return OpenExport(Uri.fromFile(file), access.fd, access::close, {}, { file.delete() })
    }

    override fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val file = File(if (folder == null) dir else File(dir, folder), name)
        file.parentFile!!.mkdirs()
        try { file.outputStream().use(write) } catch (t: Throwable) { file.delete(); throw t }
        return Uri.fromFile(file)
    }

    override fun delete(uri: Uri) { uri.path?.let { File(it).delete() } }
}

fun freeSpace(): Long = ExportDebug.fakeFreeBytes.takeIf { it >= 0 } ?: StatFs(Environment.getExternalStorageDirectory().path).availableBytes

fun ensureStorage() {
    if (freeSpace() < MinFreeBytes) throw StorageFullException()
}

internal fun fdStream(fd: java.io.FileDescriptor): OutputStream = object : java.io.FilterOutputStream(java.io.FileOutputStream(fd)) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}
