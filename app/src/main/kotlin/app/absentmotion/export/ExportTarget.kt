package app.absentmotion.export

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
    data class Low(val message: String) : StorageStatus
    data class NotEnough(val message: String) : StorageStatus
}

fun bytesNeeded(estimate: Long): Long = (estimate.coerceAtLeast(0) * 1.1).toLong() + MinFreeBytes

fun storageStatus(estimate: Long, free: Long): StorageStatus {
    val needed = bytesNeeded(estimate)
    return when {
        needed > free -> StorageStatus.NotEnough("This export needs about ${formatBytes(needed)} but only ${formatBytes(free.coerceAtLeast(0))} is free. Free up some storage or pick a smaller size.")
        free - needed < LowStorageBytes -> StorageStatus.Low("Storage is almost full: about ${formatBytes(free - needed)} will be left after this export.")
        else -> StorageStatus.Enough
    }
}

class StorageFullException : java.io.IOException("The phone ran out of storage during the export. The unfinished file was removed.")
