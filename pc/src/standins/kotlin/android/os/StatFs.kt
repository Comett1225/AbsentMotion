package android.os

class StatFs(path: String) {
    private val store = runCatching { java.nio.file.Files.getFileStore(java.nio.file.Paths.get(path)) }.getOrNull()
    val availableBytes: Long get() = store?.usableSpace ?: Long.MAX_VALUE
    val freeBytes: Long get() = store?.unallocatedSpace ?: Long.MAX_VALUE
    val totalBytes: Long get() = store?.totalSpace ?: Long.MAX_VALUE
    val blockSizeLong: Long get() = 4096
    val availableBlocksLong: Long get() = availableBytes / 4096
}
