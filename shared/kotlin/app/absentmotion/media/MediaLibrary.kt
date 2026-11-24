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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaLibrary(context: Context, private val engine: EngineThread) {
    private val resolver = context.applicationContext.contentResolver
    private val lock = Mutex()
    private val loaded = mutableMapOf<String, Triple<MediaDeclaration?, String?, Pair<Int, Int>>>()
    private val models = mutableMapOf<String, Triple<String?, Pair<Pair<Int, Int>, Int>, Int>>()
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
        val alight = scene.fromAlight
        loadPictures(scene.node.imageUris(), loaded,
            { uri -> Triple(scene.media(uri) ?: loaded[uri]?.first, withContext(Dispatchers.IO) { fileVersion(resolver, Uri.parse(uri)) }?.let { if (alight) "$it#alight" else it }, generation) },
            { uri ->
                withContext(Dispatchers.IO) { decodePicture(resolver, Uri.parse(uri), limit, alightColours = alight) }
                    .also { Log.i("AMMedia", "picture $uri ${it?.width}x${it?.height} limit $limit") }
            },
            forget = { uri -> engine.setPicture(uri, null) },
        ) { uri, bitmap -> try { engine.setPicture(uri, bitmap) } finally { bitmap.recycle() } }
        for (uri in scene.node.imageUris()) engine.publishSource(uri, pictureIdentity(loaded[uri]?.second, loaded[uri]?.third?.first, engine.instance))
        val budget = ModelDetail.budget.value
        for (uri in scene.node.modelUris()) {
            val version = withContext(Dispatchers.IO) { fileVersion(resolver, Uri.parse(modelSource(uri))) }
            if (models[uri]?.let { it.first == version && it.second == generation to budget } == true) continue
            val (mesh, textures) = withContext(Dispatchers.IO) { loadModel(resolver, uri, limit, budget) } ?: continue
            withContext(NonCancellable) {
                try {
                    if (engine.setModel(uri, mesh, textures)) models[uri] = Triple(version, generation to budget, textures.size)
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

internal fun decodePicture(resolver: ContentResolver, uri: Uri, limit: Int, keepOutOfMemory: Boolean = false, alightColours: Boolean = false): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (!alightColours) inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
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
        companionName(name, folder.list()?.toList().orEmpty())?.let { java.io.File(folder, it).canonicalFile }
            ?.takeIf { it.parentFile == folder && it.isFile && it.length() <= MAX_MODEL_BYTES }?.readBytes()
    }
}

class ModelFacts(val materials: List<ModelMaterial>, val bones: List<ModelBone>, val pictures: List<Bitmap?>)

object ModelFactsCache {
    val known = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ModelFacts>>(emptyMap())

    fun put(key: String, facts: ModelFacts) = known.update { old -> (old - key + (key to facts)).entries.toList().takeLast(24).associate { it.key to it.value } }
}

private const val MODEL_THUMB = 96

internal fun loadModel(resolver: ContentResolver, uri: String, limit: Int, budget: Int = ModelDetail.budget.value): Pair<Mesh, List<Bitmap?>>? = try {
    val source = modelSource(uri)
    val (bytes, open) = modelFiles(resolver, source)
    val mesh = decimated(parseModel(source.substringAfterLast('/'), bytes, open, modelSkinned(uri), modelLooks(uri)), budget)
    Log.i("AMMedia", "model $uri ${mesh.triangleCount} triangles ${mesh.textures.size} pictures")
    val pictures = mesh.textures.map { decodeBytes(it, limit) }
    ModelFactsCache.put(uri, ModelFacts(mesh.materials, mesh.skin?.bones.orEmpty(), pictures.map { picture ->
        picture?.let { val k = minOf(1f, MODEL_THUMB.toFloat() / maxOf(it.width, it.height)); Bitmap.createScaledBitmap(it, (it.width * k).toInt().coerceAtLeast(1), (it.height * k).toInt().coerceAtLeast(1), true).let { thumb -> if (thumb === it) it.copy(Bitmap.Config.ARGB_8888, false) else thumb } }
    }))
    mesh to pictures
} catch (e: Exception) {
    Log.w("AMMedia", "cannot read model $uri", e)
    null
} catch (_: OutOfMemoryError) { null }

internal const val MODEL_PICTURE_PREFIX = "texture-"

internal fun modelPictureName(picked: String, taken: Collection<String>): String {
    val clean = modelFileName(picked).replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).trimStart('.').ifEmpty { "picture.png" }
    val name = if ('.' in clean) clean else "$clean.png"
    val lower = taken.mapTo(HashSet()) { it.lowercase() }
    return generateSequence(1) { it + 1 }.map { if (it == 1) MODEL_PICTURE_PREFIX + name else "$MODEL_PICTURE_PREFIX$it~$name" }.first { it.lowercase() !in lower }
}

internal fun modelPictureShown(stored: String): String = Regex("^${MODEL_PICTURE_PREFIX}(\\d+~)?").replace(stored, "")

internal fun copyModelPicture(resolver: ContentResolver, folder: java.io.File, uri: Uri, picked: String): String? {
    val name = modelPictureName(picked, folder.list()?.toList().orEmpty())
    val target = java.io.File(folder, name)
    return try {
        val size = resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        if (size <= MAX_MODEL_BYTES && bounds.outWidth > 0 && bounds.outHeight > 0) name else { target.delete(); null }
    } catch (e: Exception) {
        Log.w("AMMedia", "cannot copy picture $uri", e)
        target.delete()
        null
    }
}

private fun decodeBytes(bytes: ByteArray, limit: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val ratio = maxOf(bounds.outWidth, bounds.outHeight).toDouble() / limit.coerceAtLeast(1)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        inScaled = false
        inSampleSize = Integer.highestOneBit(kotlin.math.ceil(ratio).toInt().coerceAtLeast(1))
    })
} catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

internal fun fileVersion(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.openFileDescriptor(uri, "r")?.use { fd ->
        Os.fstat(fd.fileDescriptor).run { "$st_dev:$st_ino:$st_size:" + if (android.os.Build.VERSION.SDK_INT >= 27) "${st_mtim.tv_sec}.${st_mtim.tv_nsec}" else "$st_mtime" }
    }
} catch (_: Exception) { null }

internal fun Bitmap.mirroredByExif(resolver: ContentResolver, uri: Uri): Bitmap =
    if (!exifOrientation(exifTag(resolver, uri)).second) this
    else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { setScale(-1f, 1f) }, false).also { if (it !== this) recycle() }

internal fun exifTag(resolver: ContentResolver, uri: Uri): Int = runCatching {
    resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
}.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

internal object MediaLibrarySource {
    @Volatile var linkedMedia: ((Context) -> List<DeviceMedia>)? = null
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0)
}

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
    MediaLibrarySource.linkedMedia?.invoke(context)?.let { linked ->
        val known = found.mapTo(HashSet()) { it.name to it.modified }
        found += linked.filterNot { it.name to it.modified in known }
    }
    refreshThumbnailVersions(found)
    found.filter { !demoOnly || it.album.contains("demo", ignoreCase = true) }
        .sortedWith(compareByDescending<DeviceMedia> { it.date }.thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }.thenBy { it.name })
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

suspend fun mediaThumbnail(context: Context, uri: Uri, video: Boolean? = null): Bitmap? = withContext(Dispatchers.IO) {
    val key = uri.toString()
    thumbnailLocks[key.hashCode().mod(thumbnailLocks.size)].withLock {
        cachedMediaThumbnail(uri) ?: try {
            val isVideo = video ?: ("video" in uri.pathSegments || context.contentResolver.getType(uri)?.startsWith("video/") == true)
            val system = try { thumbnail(context.contentResolver, uri, isVideo) } catch (_: Exception) { null }
            (system ?: fallbackThumbnail(context, uri, isVideo))?.also { thumbnailCache.put(key, it) }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}

private fun thumbnail(resolver: ContentResolver, uri: Uri, isVideo: Boolean): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) return resolver.loadThumbnail(uri, Size(272, 272), null)
    if (uri.authority != "media") return null
    val id = ContentUris.parseId(uri)
    @Suppress("DEPRECATION")
    return if (isVideo) MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
}

private fun fallbackThumbnail(context: Context, uri: Uri, isVideo: Boolean): Bitmap? {
    if (isVideo) return firstFrame(context, uri, 272)
    val mime = context.contentResolver.getType(uri)
    if (mime == "image/svg+xml" || uri.lastPathSegment.orEmpty().endsWith(".svg", ignoreCase = true)) return null
    val decoded = decodePicture(context.contentResolver, uri, 272) ?: return null
    val turn = exifOrientation(exifTag(context.contentResolver, uri)).first
    if (turn == 0) return decoded
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { setRotate(turn.toFloat()) }, false)
    if (rotated !== decoded) decoded.recycle()
    return rotated
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
