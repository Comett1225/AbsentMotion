package app.absentmotion.media

import android.os.Build
import android.util.Log
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
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
import app.absentmotion.model.modelUris
import app.absentmotion.platform.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaLibrary(context: Context, private val engine: EngineThread) {
    private val resolver = context.applicationContext.contentResolver
    private val lock = Mutex()
    private val loaded = mutableMapOf<String, Triple<MediaDeclaration?, String?, Pair<Int, Int>>>()
    private val models = mutableMapOf<String, Triple<String?, Pair<Int, Int>, Int>>()
    @Volatile var pinned: Scene? = null

    suspend fun update(scene: Scene) = lock.withLock {
        val kept = listOfNotNull(scene, pinned)
        val wanted = kept.flatMapTo(HashSet()) { it.node.imageUris() }
        for (uri in loaded.keys - wanted) {
            withContext(NonCancellable) {
                engine.setPicture(uri, null)
                loaded.remove(uri)
                engine.publishSource(uri, null)
            }
        }
        for (uri in models.keys - kept.flatMapTo(HashSet()) { it.node.modelUris() }) {
            withContext(NonCancellable) {
                engine.setModel(uri, null, List(models.getValue(uri).third) { null })
                models.remove(uri)
            }
        }
        prepareLocked(scene)
    }

    suspend fun prepare(scene: Scene) = lock.withLock { prepareLocked(scene) }

    private suspend fun prepareLocked(scene: Scene) {
        val limit = pictureLimit(engine.maxPictureSize(), listOfNotNull(scene, pinned).maxOf { maxOf(it.width, it.height) })
        val generation = engine.generation to limit
        loadPictures(scene.node.imageUris(), loaded,
            { uri -> Triple(scene.media(uri) ?: loaded[uri]?.first, withContext(Dispatchers.IO) { fileVersion(resolver, Uri.parse(uri)) }, generation) },
            { uri ->
                withContext(Dispatchers.IO) { decodePicture(resolver, Uri.parse(uri), limit) }
                    .also { Log.i("AMMedia", "picture $uri ${it?.width}x${it?.height} limit $limit") }
            },
            forget = { uri -> engine.setPicture(uri, null) },
        ) { uri, bitmap -> try { engine.setPicture(uri, bitmap) } finally { bitmap.recycle() } }
        for (uri in scene.node.imageUris()) engine.publishSource(uri, pictureIdentity(loaded[uri]?.second, loaded[uri]?.third?.first, engine.instance))
        for (uri in scene.node.modelUris()) {
            val version = withContext(Dispatchers.IO) { fileVersion(resolver, Uri.parse(uri)) }
            if (models[uri]?.let { it.first == version && it.second == generation } == true) continue
            val (mesh, textures) = withContext(Dispatchers.IO) { loadModel(resolver, uri, limit) } ?: continue
            withContext(NonCancellable) {
                try {
                    if (engine.setModel(uri, mesh, textures)) models[uri] = Triple(version, generation, textures.size)
                } finally { textures.forEach { it?.recycle() } }
            }
        }
    }

    internal companion object {
        suspend fun <K, P : Any> loadPictures(uris: Iterable<String>, loaded: MutableMap<String, K>, key: suspend (String) -> K,
                                              decode: suspend (String) -> P?, forget: suspend (String) -> Unit = {}, install: suspend (String, P) -> Boolean) {
            for (uri in uris) {
                val version = key(uri)
                if (loaded[uri] == version) continue
                val picture = decode(uri)
                if (picture == null) {
                    if (loaded.remove(uri) != null) forget(uri)
                    continue
                }
                withContext(NonCancellable) { if (install(uri, picture)) loaded[uri] = version }
            }
        }
    }
}

internal const val MIN_PICTURE_LIMIT = 4096

internal fun pictureIdentity(version: String?, generation: Int?, instance: Long): String? = if (version == null || generation == null) null else "$version@$instance.$generation"

internal fun pictureLimit(maxTexture: Int, canvas: Int): Int = minOf(maxTexture, maxOf(MIN_PICTURE_LIMIT, 2 * canvas))

internal fun pictureSample(side: Int, limit: Int): Int {
    var sample = 1
    while (side / (sample * 2) >= limit.coerceAtLeast(1)) sample *= 2
    return if (side / sample * 2 > limit.coerceAtLeast(1) * 3) sample * 2 else sample
}

internal fun decodePicture(resolver: ContentResolver, uri: Uri, limit: Int, keepOutOfMemory: Boolean = false): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            inScaled = false
            inSampleSize = pictureSample(maxOf(bounds.outWidth, bounds.outHeight), limit)
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
} catch (e: OutOfMemoryError) {
    if (keepOutOfMemory) throw e
    null
}

internal const val MAX_MODEL_BYTES = 256L shl 20

internal fun modelFiles(resolver: ContentResolver, uri: String): Pair<ByteArray, (String) -> ByteArray?> {
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "file") return requireNotNull(resolver.openInputStream(parsed)?.use { it.readAtMost(MAX_MODEL_BYTES.toInt() + 1) }?.also { require(it.size <= MAX_MODEL_BYTES) { "the model file is too large" } }) { "cannot open $uri" } to { _ -> null }
    val file = java.io.File(requireNotNull(parsed.path))
    require(file.length() <= MAX_MODEL_BYTES) { "the model file is too large" }
    val folder = file.parentFile!!.canonicalFile
    return file.readBytes() to { name ->
        java.io.File(folder, name.substringAfterLast('/').substringAfterLast('\\')).canonicalFile.takeIf { it.parentFile == folder && it.isFile && it.length() <= MAX_MODEL_BYTES }?.readBytes()
    }
}

internal fun loadModel(resolver: ContentResolver, uri: String, limit: Int): Pair<Mesh, List<Bitmap?>>? = try {
    val (bytes, open) = modelFiles(resolver, uri)
    val mesh = readModel(uri.substringAfterLast('/'), bytes, open)
    Log.i("AMMedia", "model $uri ${mesh.triangleCount} triangles ${mesh.textures.size} pictures")
    mesh to mesh.textures.map { decodeBytes(it, limit) }
} catch (e: Exception) {
    Log.w("AMMedia", "cannot read model $uri", e)
    null
} catch (_: OutOfMemoryError) { null }

private fun decodeBytes(bytes: ByteArray, limit: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val ratio = maxOf(bounds.outWidth, bounds.outHeight).toDouble() / limit.coerceAtLeast(1)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
        inSampleSize = Integer.highestOneBit(kotlin.math.ceil(ratio).toInt().coerceAtLeast(1))
    })
} catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

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
                       val video: Boolean, val durationMs: Long, val date: Long, val modified: Long, val svg: Boolean = false)

suspend fun deviceMedia(context: Context, demoOnly: Boolean = false): List<DeviceMedia> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val found = mutableListOf<DeviceMedia>()
    for (video in listOf(false, true)) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf("_id", "_display_name", "bucket_id", "bucket_display_name", "date_added", "date_modified", "mime_type") + if (video) arrayOf("duration") else emptyArray()
        try {
            resolver.query(collection, columns, null, null, "date_added DESC, _id DESC")?.use { c ->
                while (c.moveToNext()) found += DeviceMedia(ContentUris.withAppendedId(collection, c.getLong(0)),
                    c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", video,
                    if (video) c.getLong(7) else 0, c.getLong(4), c.getLong(5),
                    svg = !video && (c.getString(6) == "image/svg+xml" || c.getString(1).orEmpty().endsWith(".svg", ignoreCase = true)))
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

internal fun dropThumbnails() = thumbnailCache.evictAll()

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
            thumbnail(context.contentResolver, uri)?.also { thumbnailCache.put(key, it) }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}

private fun thumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) return resolver.loadThumbnail(uri, Size(272, 272), null)
    val id = ContentUris.parseId(uri)
    @Suppress("DEPRECATION")
    return if ("video" in uri.pathSegments) MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
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
