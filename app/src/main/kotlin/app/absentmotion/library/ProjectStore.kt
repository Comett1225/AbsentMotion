package app.absentmotion.library

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.Context
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaUris
import app.absentmotion.model.withCopiedLayerIds
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ProjectInfo(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val durationMs: Int,
    val sizeBytes: Long,
    val modified: Long,
    val thumbnail: File?,
    val isElement: Boolean,
    val damaged: Boolean = false,
)

class ProjectStore(private val root: File) {
    constructor(context: Context) : this(context.filesDir)

    private val dir = File(root, "projects").apply { mkdirs() }

    private val listed = ConcurrentHashMap<String, ProjectInfo>()

    val versions = ProjectVersions(File(dir, "versions"))

    fun list(includeDamaged: Boolean = false): List<ProjectInfo> = dir.listFiles { f -> f.extension == "xml" && safeSegment(f.nameWithoutExtension) }.orEmpty().mapNotNull { file ->
        val id = file.nameWithoutExtension
        val modified = file.lastModified()
        val size = file.length()
        val info = listed[id]?.takeIf { it.modified == modified && it.sizeBytes == size } ?: run {
            val text = runCatching { file.readText() }.getOrNull()
            val scene = text?.let { runCatching { Scene.parse(it) }.getOrNull() }
            if (scene == null) ProjectInfo(id, damagedTitle(text), 0, 0, 0, 0, size, modified, null, isElement = false, damaged = true)
            else ProjectInfo(
                id = id, title = scene.title, width = scene.width, height = scene.height,
                fps = scene.fps, durationMs = scene.totalTime, sizeBytes = size, modified = modified,
                thumbnail = null, isElement = scene.node["type"] == "element",
            )
        }.also { listed[id] = it }
        if (info.damaged && !includeDamaged) null else info.copy(thumbnail = thumbnailFile(id).takeIf { it.exists() })
    }

    private fun damagedTitle(text: String?): String =
        text?.take(65_536)?.let { DamagedTitle.find(it)?.groupValues?.get(1) }
            ?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&apos;", "'")?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() } ?: AppText.get(R.string.library_damaged_project)

    fun restore(id: String, version: ProjectVersions.Version) {
        val chosen = version.file.readText()
        text(id)?.let { if (runCatching { Scene.parse(it) }.isSuccess) versions.snapshot(id, it) else versions.keepDamaged(id, it) }
        save(id, chosen)
        if (version.thumbnail.exists()) version.thumbnail.copyTo(thumbnailFile(id), overwrite = true) else thumbnailFile(id).delete()
        recoveryFile(id).delete()
    }

    private fun recoveryFile(id: String) = File(root, "recovery").child("$id.xml")

    fun load(id: String): Scene? = read(id)?.let { withLinkedElements(it, id) }

    private fun read(id: String): Scene? = runCatching { Scene.parse(sceneFile(id).readText()) }.getOrNull()

    private fun withLinkedElements(scene: Scene, id: String): Scene {
        val media = ArrayList<MediaDeclaration>()
        fun follow(layers: List<XmlNode>, open: Set<String>): List<XmlNode> = layers.map { layer ->
            val linked = layer["link"]?.let(::idOfLink)?.takeIf { it !in open }
            val element = linked?.let(::read)?.withCopiedLayerIds()
            when {
                element != null -> {
                    media += element.media
                    val inner = element.node.copy(children = follow(element.node.children, open + linked))
                    layer.copy(children = layer.children.map { if (it.name == "scene") inner else it })
                }
                layer.name == "embedScene" -> layer.copy(children = layer.children.map { if (it.name == "scene") it.copy(children = follow(it.children, open)) else it })
                else -> layer
            }
        }
        val followed = Scene(scene.node.copy(children = follow(scene.node.children, setOf(id))))
        return media.fold(followed) { acc, record -> acc.withMedia(record) }
    }

    fun linkOf(id: String): String = runCatching { UUID.fromString(id).toString() }.getOrNull() ?: UUID.nameUUIDFromBytes(id.toByteArray()).toString()

    fun idOfLink(link: String): String? =
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().map { it.nameWithoutExtension }.firstOrNull { linkOf(it) == link }

    fun save(id: String, scene: Scene) = save(id, scene.toXml())

    @Synchronized
    fun save(id: String, xml: String) = writeAtomically(sceneFile(id), xml)

    fun text(id: String): String? = runCatching { sceneFile(id).readText() }.getOrNull()

    fun exists(id: String): Boolean = runCatching { sceneFile(id).exists() }.getOrDefault(false)

    fun newId(taken: Collection<String> = emptyList()): String {
        var n = System.currentTimeMillis()
        while (sceneFile("p$n").exists() || "p$n" in taken) n++
        return "p$n"
    }

    fun create(scene: Scene): String = newId().also { save(it, scene) }

    fun mediaDir(id: String) = File(root, "media").child(id)

    fun createElement(scene: Scene): String {
        val id = UUID.randomUUID().toString()
        save(id, scene)
        return id
    }

    fun duplicate(id: String): String? {
        val source = load(id) ?: return null
        val copy = newId()
        var scene = source.withTitle(AppText.get(R.string.edit_layer_copy_name, source.title))
        val from = mediaDir(id)
        try {
            if (from.isDirectory) {
                val old = folderUri(from)
                val new = folderUri(mediaDir(copy))
                from.copyRecursively(mediaDir(copy))
                scene = Scene(scene.node.mapMediaUris { if (it.startsWith(old)) new + it.substring(old.length) else it })
            }
            save(copy, scene)
        } catch (e: Exception) {
            mediaDir(copy).deleteRecursively()
            sceneFile(copy).delete()
            throw e
        }
        runCatching { thumbnailFile(id).takeIf { it.exists() }?.copyTo(thumbnailFile(copy), overwrite = true) }
        return copy
    }

    fun delete(id: String, aiDir: File? = null) {
        val own = listOfNotNull(text(id)) + versions.list(id).mapNotNull { runCatching { it.file.readText() }.getOrNull() }
        val owners = (listOf(id) + own.flatMap { runCatching { Scene.parse(it).node.mediaUris() }.getOrDefault(emptySet()) }.mapNotNull(::ownerOf)).distinct()
        sceneFile(id).delete()
        thumbnailFile(id).delete()
        recoveryFile(id).delete()
        versions.delete(id)
        val projectFiles = dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList()
        val templateFiles = File(root, "templates").listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "scene.xml") }.filter { it.exists() }
        val folders = owners.map { folderUri(mediaDir(it)) }
        val history = (File(dir, "versions").listFiles { f -> f.isDirectory }.orEmpty().flatMap { it.listFiles { f -> f.extension == "xml" }.orEmpty().toList() } +
            File(root, "recovery").listFiles { f -> f.extension == "xml" }.orEmpty())
            .filter { f -> runCatching { f.readText() }.getOrNull().let { it == null || folders.any(it::contains) || aiDir != null && it.contains("/ai/") } }
        val texts = (projectFiles + templateFiles + history).map { f -> runCatching { f.readText() }.getOrNull() ?: return }
        val used = HashSet<String>()
        val damaged = texts.filter { text -> runCatching { used += Scene.parse(text).node.mediaUris() }.isFailure }
        fun mentioned(part: String) = damaged.any { it.contains(part) || it.contains(part.replace("&", "&amp;")) }
        for (owner in owners.filter { it == id || !sceneFile(it).exists() }) {
            val folder = mediaDir(owner)
            folder.listFiles()?.filter { f ->
                val uri = f.toURI().toString()
                !mentioned(uri) && if (f.isDirectory) used.none { it.startsWith(uri) } else uri !in used
            }?.forEach { it.deleteRecursively() }
            folder.delete()
        }
        aiDir?.listFiles()?.filter { f -> f.isFile && used.none { it.endsWith("/" + f.name) } && !mentioned("/" + f.name) }?.forEach { it.delete() }
    }

    fun inMediaFolder(uri: String): Boolean = ownerOf(uri) != null

    private fun ownerOf(uri: String): String? {
        val media = folderUri(File(root, "media"))
        return uri.takeIf { it.startsWith(media) }?.removePrefix(media)?.substringBefore('/')?.takeIf(::safeSegment)
    }

    fun thumbnailFile(id: String) = dir.child("$id.png")

    private fun folderUri(dir: File) = dir.toURI().toString().trimEnd('/') + "/"

    private fun sceneFile(id: String) = dir.child("$id.xml")

    private companion object {
        val DamagedTitle = Regex("""<scene\b[^>]*?\btitle="([^"]*)"""")
    }
}

internal val ProjectId = Regex("[A-Za-z0-9_-]{1,64}")

internal fun safeSegment(name: String) = name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

internal fun File.child(name: String): File {
    require(safeSegment(name)) { "Unsafe file name" }
    return File(this, name).also { require(it.canonicalFile.parentFile == canonicalFile) { "Unsafe file name" } }
}

internal fun writeAtomically(target: File, text: String, sync: Boolean = true) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, target.name + ".tmp")
    try {
        java.io.FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            if (sync) out.fd.sync()
        }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (e: java.io.IOException) {
        temp.delete()
        throw e
    }
}
