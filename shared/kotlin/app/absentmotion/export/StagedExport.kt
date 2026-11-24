package app.absentmotion.export

import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class StagedExport(val file: File, val uri: Uri, val name: String, val ext: String, val mime: String)

object StagedExports {
    fun key(xml: String, settings: ExportSettings, kind: ExportKind, frame: Int): String =
        MessageDigest.getInstance("SHA-256").digest((xml + "\n" + settings + "\n" + kind + (if (kind == ExportKind.Frame || kind == ExportKind.FrameAs) "@$frame" else "")).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    fun find(context: Context, key: String, name: String, ext: String, mime: String): StagedExport? {
        val dir = app.absentmotion.platform.stagedExportDir(context) ?: return null
        val file = File(dir, "$key.$ext").takeIf { it.isFile && it.length() > 0 } ?: return null
        return StagedExport(file, app.absentmotion.platform.stagedExportUri(context, file), name, ext, mime)
    }

    fun store(context: Context, key: String): ExportStore? = app.absentmotion.platform.stagedExportDir(context)?.let { StageStore(context.applicationContext, it, key) }

    fun save(context: Context, staged: StagedExport, store: ExportStore): Uri =
        store.save(null, "${staged.name}.${staged.ext}", staged.mime) { out -> staged.file.inputStream().use { it.copyTo(out) } }
}

private class StageStore(private val context: Context, private val dir: File, private val key: String) : ExportStore {
    override fun open(folder: String?, name: String, mime: String): OpenExport {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val ext = name.substringAfterLast('.', "mp4")
        val part = File(dir, "$key.$ext.part")
        val file = RandomAccessFile(part, "rw")
        return OpenExport(file.fd, file::close, {
            val done = File(dir, "$key.$ext")
            if (!part.renameTo(done)) throw ExportError(app.absentmotion.i18n.txt(app.absentmotion.R.string.export_error_create, name, dir.path))
            app.absentmotion.platform.stagedExportUri(context, done)
        }, { part.delete() })
    }

    override fun save(folder: String?, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
        val file = open(folder, name, mime)
        try {
            java.io.FileOutputStream(file.fd).let(write)
            return file.publish()
        } catch (t: Throwable) {
            file.discard()
            throw t
        }
    }

    override fun delete(uri: Uri) { dir.listFiles()?.forEach { it.delete() } }

    override fun sequenceFolder(name: String, mime: String): String = name
}
