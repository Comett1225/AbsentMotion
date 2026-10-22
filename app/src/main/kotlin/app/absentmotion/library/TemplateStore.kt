package app.absentmotion.library

import app.absentmotion.platform.ContentResolver
import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.safeName
import app.absentmotion.model.Scene
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaName
import app.absentmotion.model.mediaUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files

data class SavedTemplate(val id: String, val scene: Scene, val thumbnail: File?, val modified: Long) {
    val title: String get() = scene.title
}

class TemplateStore(private val root: File, private val open: (String) -> InputStream?) {
    constructor(root: File, resolver: ContentResolver) : this(root, { uri -> resolver.openInputStream(Uri.parse(uri)) })
    constructor(context: Context) : this(File(context.filesDir, "templates"), context.applicationContext.contentResolver)

    fun list(): List<SavedTemplate> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
        val file = File(dir, "scene.xml")
        val scene = runCatching { Scene.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
        SavedTemplate(dir.name, scene, File(dir, "thumb.png").takeIf { it.exists() }, file.lastModified())
    }.sortedByDescending { it.modified }

    fun load(id: String): Scene? = runCatching { Scene.parse(File(root, "$id/scene.xml").readText()) }.getOrNull()

    fun rename(id: String, title: String) {
        val scene = load(id) ?: throw IOException("Cannot read template $id")
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
        for (uri in scene.node.mediaUris()) {
            val folder = modelFolder(uri)
            val copied = if (folder != null) copyModel(folder, File(URI(uri)).canonicalFile.name, dir)
                else runCatching { open(uri) }.getOrNull()?.use { input -> publish(dir, safeName(scene.mediaName(uri))) { input.copyTo(it) } }
            if (copied != null) moved[uri] = copied.toURI().toString()
        }
        return Scene(scene.node.mapMediaUris { moved[it] ?: it })
    }

    private fun copyModel(folder: File, main: String, dir: File): File {
        dir.mkdirs()
        val temp = Files.createTempDirectory(dir.toPath(), "copy-").toFile()
        try {
            folder.listFiles { f -> f.isFile }.orEmpty().forEach { it.copyTo(File(temp, it.name)) }
            val target = fresh(dir, folder.name)
            if (!temp.renameTo(target)) throw IOException("Cannot copy ${folder.name}")
            return File(target, main)
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun publish(dir: File, name: String, write: (java.io.OutputStream) -> Unit): File {
        dir.mkdirs()
        val temp = File.createTempFile("copy-", ".part", dir)
        try {
            temp.outputStream().use(write)
            val target = fresh(dir, name)
            if (!temp.renameTo(target)) throw IOException("Cannot copy $name")
            return target
        } finally {
            temp.delete()
        }
    }

    private fun fresh(dir: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return (sequenceOf(name) + generateSequence(2) { it + 1 }.map { name.substring(0, dot) + "-$it" + name.substring(dot) })
            .map { File(dir, it) }.first { !it.exists() }
    }

    private fun write(id: String, scene: Scene) = writeAtomically(File(root, "$id/scene.xml"), scene.toXml())
}

internal fun canReplaceProject(template: Scene?, projects: ProjectStore): Boolean =
    template != null && template.node.mediaUris().none(projects::inMediaFolder)
