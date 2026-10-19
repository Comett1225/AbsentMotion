package app.absentmotion.media

import android.util.Log
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.util.Size
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.exifOrientation
import app.absentmotion.model.imageUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaLibrary(context: Context, private val engine: EngineThread) {
    private val resolver = context.applicationContext.contentResolver
    private val lock = Mutex()
    private val loaded = mutableMapOf<String, Pair<MediaDeclaration?, String?>>()

    suspend fun update(scene: Scene, retry: Boolean = false) = lock.withLock {
        val wanted = scene.node.imageUris()
        for (uri in loaded.keys - wanted) {
            withContext(NonCancellable) {
                engine.setPicture(uri, null)
                loaded.remove(uri)
            }
        }
        if (retry) loaded.clear()
        prepareLocked(scene)
    }

    suspend fun prepare(scene: Scene) = lock.withLock { prepareLocked(scene) }

    private suspend fun prepareLocked(scene: Scene) {
        val limit = engine.maxPictureSize()
        for (uri in scene.node.imageUris()) {
            val key = scene.media(uri) to withContext(Dispatchers.IO) { fileVersion(resolver, Uri.parse(uri)) }
            if (loaded[uri] == key) continue
            val bitmap = withContext(Dispatchers.IO) { decodePicture(resolver, Uri.parse(uri), limit) }
            Log.i("AMMedia", "picture $uri ${bitmap?.width}x${bitmap?.height} limit $limit")
            try {
                withContext(NonCancellable) {
                    engine.setPicture(uri, bitmap)
                    loaded[uri] = key
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }
}

internal fun decodePicture(resolver: ContentResolver, uri: Uri, limit: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        val ratio = maxOf(bounds.outWidth, bounds.outHeight).toDouble() / limit.coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            inScaled = false
            inSampleSize = Integer.highestOneBit(ratio.toInt().coerceAtLeast(1))
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val sized = if (raw == null || maxOf(raw.width, raw.height) <= limit) raw else {
            val scale = limit.toFloat() / maxOf(raw.width, raw.height)
            Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== raw) raw.recycle() }
        }
        sized?.mirroredByExif(resolver, uri)
    }
} catch (e: Exception) {
    Log.w("AMMedia", "cannot read $uri", e)
    null
} catch (_: OutOfMemoryError) { null }

internal fun fileVersion(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.openFileDescriptor(uri, "r")?.use { fd ->
        Os.fstat(fd.fileDescriptor).run { "$st_dev:$st_ino:$st_size:${st_mtim.tv_sec}.${st_mtim.tv_nsec}" }
    }
} catch (_: Exception) { null }

internal fun Bitmap.mirroredByExif(resolver: ContentResolver, uri: Uri): Bitmap =
    if (!exifOrientation(exifTag(resolver, uri)).second) this
    else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { setScale(-1f, 1f) }, false).also { if (it !== this) recycle() }

internal fun exifTag(resolver: ContentResolver, uri: Uri): Int = runCatching {
    resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
}.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

data class DeviceMedia(val uri: Uri, val name: String, val albumId: String, val album: String,
                       val video: Boolean, val durationMs: Long, val date: Long, val modified: Long)

suspend fun deviceMedia(context: Context, demoOnly: Boolean = false): List<DeviceMedia> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val found = mutableListOf<DeviceMedia>()
    for (video in listOf(false, true)) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf("_id", "_display_name", "bucket_id", "bucket_display_name", "date_added", "date_modified") + if (video) arrayOf("duration") else emptyArray()
        try {
            resolver.query(collection, columns, null, null, "date_added DESC, _id DESC")?.use { c ->
                while (c.moveToNext()) found += DeviceMedia(ContentUris.withAppendedId(collection, c.getLong(0)),
                    c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", video,
                    if (video) c.getLong(6) else 0, c.getLong(4), c.getLong(5))
            }
        } catch (_: Exception) { }
    }
    refreshThumbnailVersions(found)
    found.filter { !demoOnly || it.album.contains("demo", ignoreCase = true) }.sortedWith(compareByDescending<DeviceMedia> { it.date }.thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0 })
}

private val thumbnailCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
private val thumbnailLocks = Array(16) { Mutex() }

private var thumbnailVersions: Map<String, Long> = emptyMap()

@Synchronized
private fun refreshThumbnailVersions(files: List<DeviceMedia>) {
    val next = files.associate { it.uri.toString() to it.modified }
    thumbnailVersions.forEach { (uri, version) -> if (next[uri] != version) thumbnailCache.remove(uri) }
    thumbnailVersions = next
}

fun cachedMediaThumbnail(uri: Uri): Bitmap? = thumbnailCache.get(uri.toString())

suspend fun mediaThumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    val key = uri.toString()
    thumbnailLocks[key.hashCode().mod(thumbnailLocks.size)].withLock {
        cachedMediaThumbnail(uri) ?: try {
            context.contentResolver.loadThumbnail(uri, Size(272, 272), null).also { thumbnailCache.put(key, it) }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}

suspend fun imageDeclaration(context: Context, uri: Uri): MediaDeclaration? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var name = uri.lastPathSegment ?: ""
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val orientation = exifOrientation(exifTag(resolver, uri)).first
        MediaDeclaration(XmlNode("media", linkedMapOf("uri" to uri.toString(), "filename" to name, "title" to name,
            "type" to (bounds.outMimeType ?: resolver.getType(uri) ?: "image/jpeg"), "size" to "$size",
            "orientation" to "$orientation", "infoUpdated" to "${System.currentTimeMillis()}",
            "width" to "${bounds.outWidth}", "height" to "${bounds.outHeight}")))
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}
