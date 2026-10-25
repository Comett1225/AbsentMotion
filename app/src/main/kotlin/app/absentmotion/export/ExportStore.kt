package app.absentmotion.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class PendingExports(private val file: File, private val mine: MutableSet<String> = ThisProcess) {
    fun add(entry: String): Boolean = synchronized(Lock) {
        mine += entry
        try {
            file.parentFile?.mkdirs()
            file.appendText(entry + "\n")
            true
        } catch (e: IOException) {
            false
        }
    }

    fun remove(entry: String): Boolean = synchronized(Lock) {
        mine -= entry
        try {
            val left = read().filter { it != entry }
            if (left.isEmpty()) file.delete() else replace(left)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun abandoned(): List<String> = synchronized(Lock) { (try { read() } catch (e: IOException) { emptyList() }).filter { it !in mine }.distinct() }

    private fun read() = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun replace(entries: List<String>) {
        val next = File(file.path + ".new")
        next.writeText(entries.joinToString("\n", postfix = "\n"))
        if (!next.renameTo(file)) {
            next.delete()
            throw IOException("cannot replace " + file.name)
        }
    }

    private companion object {
        val Lock = Any()
        val ThisProcess = HashSet<String>()
    }
}

class MediaStoreExport(context: Context) : ExportStore {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val scoped = Build.VERSION.SDK_INT >= 29
    private val pending = PendingExports(File(app.noBackupFilesDir, "export-pending.txt"))

    fun discardAbandoned() {
        if (!swept.compareAndSet(false, true)) return
        for (entry in pending.abandoned()) if (runCatching { abandon(entry) }.getOrDefault(false)) pending.remove(entry)
    }

    private fun abandon(entry: String): Boolean {
        if (entry.startsWith("/")) return File(entry).let { !it.exists() || it.delete() }
        val uri = Uri.parse(entry)
        return when (pendingState(uri) ?: return false) {
            -1, 0 -> true
            else -> { resolver.delete(uri, null, null); true }
        }
    }

    private fun pendingState(uri: Uri): Int? {
        val columns = arrayOf(MediaStore.MediaColumns.IS_PENDING)
        val cursor = when {
            Build.VERSION.SDK_INT >= 30 -> resolver.query(uri, columns, Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }, null)
            Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION") resolver.query(MediaStore.setIncludePending(uri), columns, null, null, null)
            else -> return -1
        }
        return cursor?.use { if (it.moveToFirst()) it.getInt(0) else -1 }
    }

    private fun discard(uri: Uri) {
        resolver.delete(uri, null, null)
        pending.remove(uri.toString())
    }

    private fun collection(mime: String): Pair<Uri, String> {
        val volume = if (scoped) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external"
        return when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume) to Environment.DIRECTORY_MOVIES
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume) to Environment.DIRECTORY_PICTURES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Files.getContentUri(volume) to Environment.DIRECTORY_DOCUMENTS
        }
    }

    private fun folderPath(root: String, folder: String?) = "$root/$AppFolder" + (folder?.let { "/$it" } ?: "")

    private fun done(uri: Uri, name: String) {
        val published = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        if (published <= 0) throw ExportError(txt(R.string.export_error_write, name))
        pending.remove(uri.toString())
    }

    override fun open(folder: String?, name: String, mime: String): OpenExport = if (scoped) openScoped(folder, name, mime) else openLegacy(folder, name, mime)

    private fun openScoped(folder: String?, name: String, mime: String): OpenExport {
        val (collection, root) = collection(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folderPath(root, folder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw ExportError(txt(R.string.export_error_create, name, root))
        pending.add(uri.toString())
        val file = try { resolver.openFileDescriptor(uri, "rw") ?: throw ExportError(txt(R.string.export_error_write, name)) }
            catch (t: Throwable) { runCatching { discard(uri) }; throw t }
        return OpenExport(file.fileDescriptor, file::close, { done(uri, name); uri }, { discard(uri) })
    }

    private fun openLegacy(folder: String?, name: String, mime: String): OpenExport {
        val (collection, root) = collection(mime)
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStorageDirectory(), folderPath(root, folder))
        dir.mkdirs()
        val part = File(dir, ".${System.nanoTime()}.part")
        pending.add(part.path)
        val access = try { java.io.RandomAccessFile(part, "rw") } catch (t: Throwable) {
            pending.remove(part.path)
            throw ExportError(txt(R.string.export_error_create, name, root), t)
        }
        return OpenExport(access.fd, access::close, { publishLegacy(part, dir, name, mime, collection, root) }, { if (part.delete() || !part.exists()) pending.remove(part.path) })
    }

    private fun publishLegacy(part: File, dir: File, name: String, mime: String, collection: Uri, root: String): Uri {
        val target = freeFile(dir, name)
        if (!part.renameTo(target)) throw ExportError(txt(R.string.export_error_write, name))
        pending.remove(part.path)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, target.path)
        }
        val uri = resolver.insert(collection, values)
        MediaScannerConnection.scanFile(app, arrayOf(target.path), arrayOf(mime), null)
        return uri ?: throw ExportError(txt(R.string.export_error_create, target.name, root))
    }

    override fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val file = open(folder, name, mime)
        return try {
            fdStream(file.fd).use(write)
            file.publish()
        } catch (t: Throwable) {
            file.discard()
            throw t
        }
    }

    override fun delete(uri: Uri) { discard(uri) }

    private companion object {
        val swept = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

internal fun freeFile(dir: File, name: String): File {
    dir.mkdirs()
    val base = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) name else "$base ($it)$extension") }.first { !it.exists() }
}

class FolderExport(private val dir: File) : ExportStore {
    override fun open(folder: String?, name: String, mime: String): OpenExport {
        val file = File(if (folder == null) dir else File(dir, folder), name)
        file.parentFile!!.mkdirs()
        val access = java.io.RandomAccessFile(file, "rw")
        access.setLength(0)
        return OpenExport(access.fd, access::close, { Uri.fromFile(file) }, { file.delete() })
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
