package app.absentmotion.library

import app.absentmotion.platform.ContentResolver
import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.safeName
import app.absentmotion.model.Scene
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class SavedTemplate(val id: String, val scene: Scene, val thumbnail: File?, val modified: Long) {
    val title: String get() = scene.title
}

class TemplateStore(private val root: File, private val resolver: ContentResolver) {
    constructor(context: Context) : this(File(context.filesDir, "templates"), context.applicationContext.contentResolver)

    fun list(): List<SavedTemplate> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
        val file = File(dir, "scene.xml")
        val scene = runCatching { Scene.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
        SavedTemplate(dir.name, scene, File(dir, "thumb.png").takeIf { it.exists() }, file.lastModified())
    }.sortedByDescending { it.modified }

    fun load(id: String): Scene? = runCatching { Scene.parse(File(root, "$id/scene.xml").readText()) }.getOrNull()

    fun rename(id: String, title: String) {
        val scene = load(id) ?: return
        write(id, scene.withTitle(title))
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    suspend fun add(scene: Scene, timeMs: Int, engine: EngineThread): String = withContext(Dispatchers.IO) {
        var n = System.currentTimeMillis()
        while (File(root, "t$n").exists()) n++
        val id = "t$n"
        try {
            val stored = localize(scene, File(root, "$id/media"))
            write(id, stored)
            val longSide = maxOf(stored.width, stored.height, 1)
            engine.stillPng(stored.toXml(), timeMs, (256 * stored.width / longSide).coerceAtLeast(1), (256 * stored.height / longSide).coerceAtLeast(1), File(root, "$id/thumb.png"))
        } catch (t: Throwable) {
            delete(id)
            throw t
        }
        id
    }

    suspend fun adopt(projects: ProjectStore, id: String, engine: EngineThread): String? {
        val scene = projects.load(id) ?: return null
        val template = try { add(scene, 0, engine) } catch (_: IOException) { return null }
        return withContext(Dispatchers.IO) {
            if (canReplaceProject(load(template), projects)) template.also { projects.delete(id) }
            else null.also { delete(template) }
        }
    }

    fun createProject(scene: Scene, projects: ProjectStore): String {
        val id = projects.newId()
        try {
            projects.save(id, localize(scene, projects.mediaDir(id)))
        } catch (t: Throwable) {
            projects.delete(id)
            throw t
        }
        return id
    }

    fun localize(scene: Scene, dir: File): Scene {
        val moved = HashMap<String, String>()
        scene.node.mediaUris().forEachIndexed { i, uri ->
            val name = (scene.media(uri)?.filename?.takeIf { it.isNotEmpty() } ?: Uri.parse(uri).lastPathSegment ?: "media").substringAfterLast('/')
            val target = File(dir, "${i + 1}_${safeName(name)}")
            val copied = runCatching {
                resolver.openInputStream(Uri.parse(uri))?.use { input ->
                    dir.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                    true
                }
            }.getOrNull() == true
            if (copied) moved[uri] = target.toURI().toString() else target.delete()
        }
        return Scene(scene.node.mapMediaUris { moved[it] ?: it })
    }

    private fun write(id: String, scene: Scene) {
        val dir = File(root, id).apply { mkdirs() }
        val temp = File(dir, "scene.xml.tmp")
        temp.writeText(scene.toXml())
        java.nio.file.Files.move(temp.toPath(), File(dir, "scene.xml").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun canReplaceProject(template: Scene?, projects: ProjectStore): Boolean =
    template != null && template.node.mediaUris().none(projects::inMediaFolder)
