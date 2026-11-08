package app.absentmotion.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.File
import java.io.OutputStream

class MediaStoreExport(context: Context) : ExportStore {
    fun discardAbandoned() {
        synchronized(Lock) {
            val listed = if (pending.exists()) pending.readLines().filter { it.isNotBlank() } else emptyList()
            val left = listed.filter { it in mine || File(it).let { f -> f.exists() && !(f.name.endsWith(".part") && f.delete()) } }
            if (left.isEmpty()) pending.delete() else pending.writeText(left.joinToString("\n", postfix = "\n"))
        }
        val stale = System.currentTimeMillis() - 3_600_000L
        File(android.content.Desk.context.cacheDir, "export").listFiles()?.filter { it.isFile && it.lastModified() < stale }?.forEach { it.delete() }
    }

    private val pending get() = File(android.content.Desk.context.filesDir, "export-pending.txt")

    private fun note(path: String, open: Boolean) = synchronized(Lock) {
        runCatching {
            if (open) { mine += path; pending.parentFile?.mkdirs(); pending.appendText(path + "\n") }
            else {
                mine -= path
                val left = if (pending.exists()) pending.readLines().filter { it.isNotBlank() && it != path } else emptyList()
                if (left.isEmpty()) pending.delete() else pending.writeText(left.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private companion object {
        val Lock = Any()
        val mine = HashSet<String>()
    }

    private fun dir(mime: String, folder: String?): File {
        val root = (System.getProperty("am.exports")?.let(::File) ?: Environment.getExternalStorageDirectory()).resolve(rootFor(mime)).resolve(AppFolder)
        return if (folder == null) root else root.resolve(folder)
    }

    override fun open(folder: String?, name: String, mime: String): OpenExport {
        val dir = dir(mime, folder).also { it.mkdirs() }
        val part = File(dir, ".${System.nanoTime()}.part")
        val access = try { java.io.RandomAccessFile(part, "rw") } catch (t: Throwable) { throw ExportError(txt(R.string.export_error_create, name, dir.path), t) }
        note(part.path, true)
        return OpenExport(access.fd, access::close, {
            val target = freeFile(dir, name)
            if (!part.renameTo(target)) throw ExportError(txt(R.string.export_error_write, name))
            note(part.path, false)
            Uri.fromFile(target)
        }, { part.delete(); note(part.path, false) })
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

    override fun location(where: String): String {
        val first = where.substringBefore('/')
        val local = where.replaceFirst(first, rootFor(first))
        return if (app.absentmotion.desk.DeskOs.windows || local.startsWith("/")) local else "~/$local"
    }

    override fun delete(uri: Uri) { uri.toFile()?.delete() }

    override fun sequenceFolder(name: String, mime: String): String = freeFolder(dir(mime, null), name)

    private fun rootFor(mimeOrRoot: String): String = when {
        mimeOrRoot.startsWith("video/") || mimeOrRoot == Environment.DIRECTORY_MOVIES -> app.absentmotion.desk.DeskOs.videosFolder
        mimeOrRoot.startsWith("image/") || mimeOrRoot == Environment.DIRECTORY_PICTURES -> app.absentmotion.desk.DeskOs.picturesFolder
        mimeOrRoot.startsWith("audio/") || mimeOrRoot == Environment.DIRECTORY_MUSIC -> app.absentmotion.desk.DeskOs.musicFolder
        else -> app.absentmotion.desk.DeskOs.documentsFolder
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

    override fun sequenceFolder(name: String, mime: String): String = freeFolder(dir, name)
}

fun freeSpace(): Long = ExportDebug.fakeFreeBytes.takeIf { it >= 0 } ?: StatFs(Environment.getExternalStorageDirectory().path).availableBytes

fun ensureStorage() {
    if (freeSpace() < MinFreeBytes) throw StorageFullException()
}

internal fun fdStream(fd: java.io.FileDescriptor): OutputStream = object : java.io.FilterOutputStream(java.io.FileOutputStream(fd)) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}
