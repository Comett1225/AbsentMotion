package app.absentmotion.library

import app.absentmotion.platform.Context
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaUris
import java.io.File
import java.util.UUID

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
)

class ProjectStore(private val root: File) {
    constructor(context: Context) : this(context.filesDir)

    private val dir = File(root, "projects").apply { mkdirs() }

    fun list(): List<ProjectInfo> = dir.listFiles { f -> f.extension == "xml" }.orEmpty().mapNotNull { file ->
        val scene = runCatching { Scene.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
        ProjectInfo(
            id = file.nameWithoutExtension, title = scene.title, width = scene.width, height = scene.height,
            fps = scene.fps, durationMs = scene.totalTime, sizeBytes = file.length(), modified = file.lastModified(),
            thumbnail = thumbnailFile(file.nameWithoutExtension).takeIf { it.exists() },
            isElement = scene.node["type"] == "element",
        )
    }

    fun load(id: String): Scene? = read(id)?.let { withLinkedElements(it, id) }

    private fun read(id: String): Scene? = runCatching { Scene.parse(sceneFile(id).readText()) }.getOrNull()

    private fun withLinkedElements(scene: Scene, id: String): Scene {
        val media = ArrayList<MediaDeclaration>()
        fun follow(layers: List<XmlNode>, open: Set<String>): List<XmlNode> = layers.map { layer ->
            val linked = layer["link"]?.let(::idOfLink)?.takeIf { it !in open }
            val element = linked?.let(::read)
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

    fun save(id: String, scene: Scene) {
        val temp = File(dir, "$id.xml.tmp")
        temp.writeText(scene.toXml())
        java.nio.file.Files.move(temp.toPath(), sceneFile(id).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun newId(): String {
        var n = System.currentTimeMillis()
        while (sceneFile("p$n").exists()) n++
        return "p$n"
    }

    fun create(scene: Scene): String = newId().also { save(it, scene) }

    fun mediaDir(id: String) = File(root, "media/$id")

    fun createElement(scene: Scene): String {
        val id = UUID.randomUUID().toString()
        save(id, scene)
        return id
    }

    fun duplicate(id: String): String? {
        val source = load(id) ?: return null
        val copy = newId()
        var scene = source.withTitle(source.title + " Copy")
        val from = mediaDir(id)
        if (from.isDirectory) {
            val old = folderUri(from)
            val new = folderUri(mediaDir(copy))
            from.copyRecursively(mediaDir(copy))
            scene = Scene(scene.node.mapMediaUris { if (it.startsWith(old)) new + it.substring(old.length) else it })
        }
        save(copy, scene)
        thumbnailFile(id).takeIf { it.exists() }?.copyTo(thumbnailFile(copy), overwrite = true)
        return copy
    }

    fun delete(id: String) {
        val owners = (listOf(id) + (read(id)?.node?.mediaUris().orEmpty().mapNotNull(::ownerOf))).distinct()
        sceneFile(id).delete()
        thumbnailFile(id).delete()
        val used = dir.listFiles { f -> f.extension == "xml" }.orEmpty()
            .mapNotNull { f -> runCatching { Scene.parse(f.readText()).node.mediaUris() }.getOrNull() }.flatten().toSet()
        for (owner in owners.filter { it == id || !sceneFile(it).exists() }) {
            val folder = mediaDir(owner)
            folder.listFiles()?.filter { it.toURI().toString() !in used }?.forEach { it.deleteRecursively() }
            folder.delete()
        }
    }

    private fun ownerOf(uri: String): String? {
        val media = folderUri(File(root, "media"))
        return uri.takeIf { it.startsWith(media) }?.removePrefix(media)?.substringBefore('/')?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    fun thumbnailFile(id: String) = File(dir, "$id.png")

    private fun folderUri(dir: File) = dir.toURI().toString().trimEnd('/') + "/"

    private fun sceneFile(id: String) = File(dir, "$id.xml")
}
