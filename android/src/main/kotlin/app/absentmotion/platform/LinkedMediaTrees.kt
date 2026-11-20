package app.absentmotion.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import app.absentmotion.R
import app.absentmotion.audio.DeviceAudio
import app.absentmotion.media.DeviceMedia
import app.absentmotion.media.MediaLibrarySource
import app.absentmotion.media.openDemuxer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal object LinkedTreeStore {
    const val PREFS_NAME = "library"
    const val PREFS_KEY = "linkedTrees"
    const val MAX_DEPTH = 4
    const val MAX_FILES = 4000
    const val DIRECTORY_MIME = "vnd.android.document/directory"

    enum class MediaKind { Video, Image, Audio }

    data class ChildEntry(
        val docId: String,
        val name: String,
        val mime: String?,
        val lastModifiedMs: Long,
        val size: Long,
    )

    data class ScannedEntry(
        val docId: String,
        val name: String,
        val mime: String,
        val kind: MediaKind,
        val lastModifiedMs: Long,
        val size: Long,
    )

    fun decode(raw: String?): List<String> =
        raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun encode(uris: List<String>): String =
        uris.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

    fun withTree(current: List<String>, uri: String): List<String> {
        val clean = uri.trim()
        if (clean.isEmpty()) return current
        return listOf(clean) + current.filter { it != clean }
    }

    fun withoutTree(current: List<String>, uri: String): List<String> = current.filter { it != uri }

    fun isHidden(name: String): Boolean = name.startsWith(".")

    fun isDirectory(mime: String?): Boolean = mime == DIRECTORY_MIME

    fun isSvg(mime: String?, name: String): Boolean =
        mime.equals("image/svg+xml", ignoreCase = true) || name.endsWith(".svg", ignoreCase = true)

    fun secondsOf(millis: Long): Long = if (millis > 0L) millis / 1000L else 0L

    fun classifyMime(mime: String?): MediaKind? {
        val lower = mime?.trim()?.lowercase() ?: return null
        return when {
            lower == DIRECTORY_MIME -> null
            lower.startsWith("video/") -> MediaKind.Video
            lower.startsWith("image/") -> MediaKind.Image
            lower.startsWith("audio/") || lower == "application/ogg" || lower == "application/x-flac" -> MediaKind.Audio
            else -> null
        }
    }

    fun walk(
        rootId: String,
        maxDepth: Int = MAX_DEPTH,
        maxFiles: Int = MAX_FILES,
        accept: (MediaKind) -> Boolean = { true },
        listChildren: (String) -> List<ChildEntry>?,
    ): List<ScannedEntry> {
        val visited = HashSet<String>()
        if (!visited.add(rootId)) return emptyList()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(rootId to 0)
        val found = ArrayList<ScannedEntry>()
        while (queue.isNotEmpty() && found.size < maxFiles) {
            val (parentId, depth) = queue.removeFirst()
            val children = listChildren(parentId) ?: continue
            for (child in children) {
                if (child.docId.isBlank() || child.name.isBlank() || isHidden(child.name)) continue
                if (isDirectory(child.mime)) {
                    if (depth < maxDepth && visited.add(child.docId)) queue.addLast(child.docId to (depth + 1))
                } else if (child.size != 0L) {
                    val kind = classifyMime(child.mime)?.takeIf(accept) ?: continue
                    if (visited.add(child.docId)) {
                        found += ScannedEntry(child.docId, child.name, child.mime!!, kind, child.lastModifiedMs, child.size)
                        if (found.size >= maxFiles) break
                    }
                }
            }
        }
        return found
    }
}

internal object LinkedMediaTrees {
    private const val MAX_CACHE_ENTRIES = 4096

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    private data class VideoMeta(val stamp: String, val durationMs: Long)
    private data class AudioMeta(val stamp: String, val durationMs: Long, val title: String, val album: String, val artist: String)

    @Volatile private var owner: Any? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var launchPicker: () -> Unit = {}

    private val treeNames = ConcurrentHashMap<String, String>()
    private val videoMetaCache = ConcurrentHashMap<String, VideoMeta>()
    private val audioMetaCache = ConcurrentHashMap<String, AudioMeta>()

    fun install(owner: Any, context: Context, launch: () -> Unit) {
        this.owner = owner
        this.appContext = context.applicationContext
        this.launchPicker = launch
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.launchPicker = {}
    }

    fun openPicker() {
        runCatching { launchPicker() }.onFailure {
            appContext?.let { c -> Toast.makeText(c, app.absentmotion.i18n.AppText.get(R.string.media_drive_cannot_open), Toast.LENGTH_SHORT).show() }
        }
    }

    fun picked(context: Context, uri: Uri) {
        val app = context.applicationContext
        appContext = app
        if (runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isFailure) return
        val key = uri.toString()
        save(app, LinkedTreeStore.withTree(saved(app), key))
        treeNames.remove(key)
        MediaLibrarySource.revision.value++
    }

    fun saved(context: Context): List<String> =
        LinkedTreeStore.decode(context.getSharedPreferences(LinkedTreeStore.PREFS_NAME, Context.MODE_PRIVATE).getString(LinkedTreeStore.PREFS_KEY, null))

    private fun save(context: Context, uris: List<String>) {
        context.getSharedPreferences(LinkedTreeStore.PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (uris.isEmpty()) remove(LinkedTreeStore.PREFS_KEY) else putString(LinkedTreeStore.PREFS_KEY, LinkedTreeStore.encode(uris))
        }
    }

    fun folderName(uri: Uri): String? {
        val context = appContext ?: return null
        val tree = matchingTree(context, uri) ?: return null
        return treeNames[tree.toString()] ?: treeName(context, tree)
    }

    fun unlinkFor(uri: Uri) {
        val context = appContext ?: return
        val tree = matchingTree(context, uri) ?: return
        val auth = tree.authority
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        val remaining = saved(context).filterNot { raw ->
            val candidate = raw.toUri()
            raw == tree.toString() || (candidate.authority == auth && runCatching { DocumentsContract.getTreeDocumentId(candidate) }.getOrNull() == treeId)
        }
        save(context, remaining)
        treeNames.remove(tree.toString())
        MediaLibrarySource.revision.value++
    }

    fun listVisual(context: Context): List<DeviceMedia> {
        val app = context.applicationContext
        appContext = app
        val resolver = app.contentResolver
        val found = ArrayList<DeviceMedia>()
        for (raw in saved(app)) {
            val treeUri = runCatching { raw.toUri() }.getOrNull() ?: continue
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            val scanned = LinkedTreeStore.walk(rootId, accept = { it == LinkedTreeStore.MediaKind.Video || it == LinkedTreeStore.MediaKind.Image }) { queryChildren(resolver, treeUri, it) }
            if (scanned.isEmpty()) continue
            val folder = treeName(app, treeUri)
            val albumId = "tree:$raw"
            for (item in scanned) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.docId)
                val key = docUri.toString()
                val stamp = "${item.lastModifiedMs}:${item.size}"
                val video = item.kind == LinkedTreeStore.MediaKind.Video
                val durationMs = if (video) videoDuration(app, docUri, key, stamp) else 0L
                val date = LinkedTreeStore.secondsOf(item.lastModifiedMs)
                val svg = !video && LinkedTreeStore.isSvg(item.mime, item.name)
                found += DeviceMedia(docUri, item.name, albumId, folder, video, durationMs, date, date, svg)
            }
        }
        return found
    }

    fun listSounds(context: Context): List<DeviceAudio> {
        val app = context.applicationContext
        appContext = app
        val resolver = app.contentResolver
        val found = ArrayList<DeviceAudio>()
        for (raw in saved(app)) {
            val treeUri = runCatching { raw.toUri() }.getOrNull() ?: continue
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            val scanned = LinkedTreeStore.walk(rootId, accept = { it == LinkedTreeStore.MediaKind.Audio }) { queryChildren(resolver, treeUri, it) }
            if (scanned.isEmpty()) continue
            val folder = treeName(app, treeUri).replace('/', ' ').trim()
            for (item in scanned) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.docId)
                val key = docUri.toString()
                val stamp = "${item.lastModifiedMs}:${item.size}"
                val meta = audioMeta(app, docUri, key, stamp)
                if (meta.durationMs <= 0L) continue
                val title = meta.title.ifBlank { item.name.substringBeforeLast('.') }
                val date = LinkedTreeStore.secondsOf(item.lastModifiedMs)
                found += DeviceAudio(key, title, meta.durationMs, date, item.name, meta.album, meta.artist, folder)
            }
        }
        return found
    }

    private fun matchingTree(context: Context, documentUri: Uri): Uri? {
        if (!DocumentsContract.isTreeUri(documentUri)) return null
        val docTreeId = runCatching { DocumentsContract.getTreeDocumentId(documentUri) }.getOrNull() ?: return null
        val auth = documentUri.authority ?: return null
        return saved(context).firstNotNullOfOrNull { raw ->
            val tree = runCatching { raw.toUri() }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (tree.authority == auth && runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() == docTreeId) tree else null
        }
    }

    private fun treeName(context: Context, treeUri: Uri): String {
        val key = treeUri.toString()
        treeNames[key]?.let { return it }
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return key
        val queried = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (treeUri.authority == "com.android.externalstorage.documents") {
            val volume = rootId.substringBefore(':')
            val path = rootId.substringAfter(':', "").trim('/')
            if (path.isEmpty()) {
                val manager = context.getSystemService(StorageManager::class.java)
                val volumeName = runCatching {
                    val match = if (volume == "primary") manager?.storageVolumes?.firstOrNull { it.isPrimary }
                    else manager?.storageVolumes?.firstOrNull { it.uuid.equals(volume, true) }
                    match?.getDescription(context)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                val resolved = volumeName ?: queried?.takeUnless { it.equals(volume, true) } ?: queried
                if (resolved != null) {
                    treeNames[key] = resolved
                    return resolved
                }
                return volume
            }
            val resolved = queried ?: path.substringAfterLast('/')
            treeNames[key] = resolved
            return resolved
        }
        if (queried != null) {
            treeNames[key] = queried
            return queried
        }
        return rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { key }
    }

    private fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<LinkedTreeStore.ChildEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        return try {
            resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idCol < 0) return@use emptyList()
                val items = ArrayList<LinkedTreeStore.ChildEntry>()
                while (c.moveToNext()) {
                    val id = c.getString(idCol) ?: continue
                    val name = if (nameCol >= 0) c.getString(nameCol) else null
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val mod = if (modCol >= 0 && !c.isNull(modCol)) c.getLong(modCol) else 0L
                    val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else -1L
                    items += LinkedTreeStore.ChildEntry(id, name ?: id.substringAfterLast('/'), mime, mod, size)
                }
                items
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun videoDuration(context: Context, uri: Uri, key: String, stamp: String): Long {
        videoMetaCache[key]?.takeIf { it.stamp == stamp }?.let { return it.durationMs }
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
        if (videoMetaCache.size >= MAX_CACHE_ENTRIES) videoMetaCache.clear()
        videoMetaCache[key] = VideoMeta(stamp, durationMs)
        return durationMs
    }

    private fun audioMeta(context: Context, uri: Uri, key: String, stamp: String): AudioMeta {
        audioMetaCache[key]?.takeIf { it.stamp == stamp }?.let { return it }
        val retriever = MediaMetadataRetriever()
        var duration = 0L
        var title = ""
        var album = ""
        var artist = ""
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        if (duration <= 0L) {
            duration = runCatching {
                val extractor = openDemuxer(context, uri, "audio/")
                try {
                    (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                        .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        ?.let { if (it.containsKey(MediaFormat.KEY_DURATION)) (it.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L) else 0L }
                        ?: 0L
                } finally {
                    extractor.release()
                }
            }.getOrDefault(0L)
        }
        if (audioMetaCache.size >= MAX_CACHE_ENTRIES) audioMetaCache.clear()
        return AudioMeta(stamp, duration, title, album, artist).also { audioMetaCache[key] = it }
    }
}
