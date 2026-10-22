package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Uri
import java.io.FileDescriptor
import java.io.OutputStream

interface ExportStore {
    fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri

    fun open(folder: String?, name: String, mime: String): OpenExport

    fun location(where: String): String = where

    fun delete(uri: Uri)
}

class OpenExport(val uri: Uri, val fd: FileDescriptor, private val close: () -> Unit, private val finish: () -> Unit, private val remove: () -> Unit) {
    fun publish() {
        close()
        finish()
    }

    fun discard() {
        runCatching(close)
        remove()
    }
}

const val AppFolder = "AbsentMotion"

const val MinFreeBytes = 32L shl 20

const val LowStorageBytes = 1L shl 30

sealed interface StorageStatus {
    data object Enough : StorageStatus
    data class Low(val message: UiText) : StorageStatus
    data class NotEnough(val message: UiText) : StorageStatus
}

fun bytesNeeded(estimate: Long): Long = (estimate.coerceAtLeast(0) * 1.1).toLong() + MinFreeBytes

fun storageStatus(estimate: Long, free: Long): StorageStatus {
    val needed = bytesNeeded(estimate)
    return when {
        needed > free -> StorageStatus.NotEnough(txt(R.string.export_storage_not_enough, formatBytes(needed), formatBytes(free.coerceAtLeast(0))))
        free - needed < LowStorageBytes -> StorageStatus.Low(txt(R.string.export_storage_low, formatBytes(free - needed)))
        else -> StorageStatus.Enough
    }
}

class StorageFullException : java.io.IOException() {
    override val message: String get() = AppText.get(R.string.export_storage_full)
}

class ExportError(val text: UiText, cause: Throwable? = null) : IllegalStateException(cause) {
    override val message: String get() = AppText.get(text)
}

inline fun exportCheck(ok: Boolean, text: () -> UiText) {
    if (!ok) throw ExportError(text())
}
