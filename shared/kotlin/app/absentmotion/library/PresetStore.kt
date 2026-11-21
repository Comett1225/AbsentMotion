package app.absentmotion.library

import app.absentmotion.editor.applyPreset
import app.absentmotion.editor.imageLayer
import app.absentmotion.editor.videoLayer
import app.absentmotion.engine.EngineThread
import app.absentmotion.media.imageDeclaration
import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.storedMediaUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class SavedPreset(val id: String, val scene: Scene) {
    val title: String get() = scene.title
    val layer: Layer? get() = scene.layers.firstOrNull()
    val ownLook: Boolean get() = layer?.let { it.kind != "audio" && it.node.storedMediaUris().isEmpty() } == true
    val pictureIndex: Int get() = Math.floorMod(id.hashCode(), PresetPictures.size)

    val placeholder: PresetPlaceholder get() {
        val tag = scene.node.child("placeholder")
        tag?.get("asset")?.let(PresetPictures::indexOf)?.takeIf { it >= 0 }?.let { return PresetPlaceholder.Picture(it) }
        (tag?.get("fillVideo") ?: tag?.get("fillImage"))?.let { uri -> scene.media(uri)?.let { return PresetPlaceholder.Media(it) } }
        return if (ownLook) PresetPlaceholder.Own else PresetPlaceholder.Picture(pictureIndex)
    }
}

sealed interface PresetPlaceholder {
    data object Own : PresetPlaceholder
    data class Picture(val index: Int) : PresetPlaceholder
    data class Media(val media: MediaDeclaration) : PresetPlaceholder
}

val PresetPictures = listOf(
    "tutorial/picture.jpg", "tutorial/picture2.jpg", "presets/placeholders/hills.webp",
    "presets/placeholders/city.webp", "presets/placeholders/shapes.webp", "presets/placeholders/ocean.webp",
)

fun placeholderScene(preset: SavedPreset, media: MediaDeclaration?): Scene? {
    val layer = preset.layer ?: return null
    val node = preset.scene.node
    val base = Scene(node.copy(children = node.children.filter { it.name !in Scene.LAYER_TAGS && it.name != "media" && it.name != "placeholder" }))
    if (media == null) return if (preset.ownLook) base.add(layer) else null
    val frame = base.frameAt(layer.startTime)
    val made = (if (media.duration > 0) base.videoLayer(media, frame) else base.imageLayer(media, frame, 1)) ?: return null
    val timed = made.copy(node = made.node.with("id", "${layer.id}").with("startTime", "${layer.startTime}").with("endTime", "${layer.endTime}"))
    return base.add(applyPreset(timed, layer), listOf(media))
}

internal fun Scene.withPlaceholder(choice: PresetPlaceholder): Scene {
    val tag = when (choice) {
        PresetPlaceholder.Own -> null
        is PresetPlaceholder.Picture -> XmlNode("placeholder", mapOf("asset" to PresetPictures[choice.index]))
        is PresetPlaceholder.Media -> XmlNode("placeholder", mapOf((if (choice.media.duration > 0) "fillVideo" else "fillImage") to choice.media.uri))
    }
    val placed = Scene(node.copy(children = node.children.filter { it.name != "placeholder" } + listOfNotNull(tag)))
    return if (choice is PresetPlaceholder.Media) placed.withMedia(choice.media) else placed
}

suspend fun placeholderScene(context: Context, preset: SavedPreset, choice: PresetPlaceholder): Scene? = withContext(Dispatchers.IO) {
    val media = when (choice) {
        PresetPlaceholder.Own -> null
        is PresetPlaceholder.Media -> choice.media
        is PresetPlaceholder.Picture -> presetPicture(context, choice.index) ?: return@withContext null
    }
    placeholderScene(preset, media)
}

private suspend fun presetPicture(context: Context, index: Int): MediaDeclaration? {
    val asset = PresetPictures[index]
    val file = File(context.filesDir, "media/preset-pictures/${asset.substringAfterLast('/')}")
    if (!file.isFile) runCatching {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, "${file.name}.part")
        context.assets.open(asset).use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(file)) part.delete()
    }
    return if (file.isFile) imageDeclaration(context, Uri.fromFile(file)) else null
}

suspend fun presetCardPicture(context: Context, preset: SavedPreset, engine: EngineThread): ByteArray? = withContext(Dispatchers.IO) {
    val choice = preset.placeholder
    val key = sha256String(preset.scene.node.toXml() + choice)
    val cached = File(context.cacheDir, "presets/$key.png")
    if (cached.isFile && cached.length() > 0) runCatching { cached.readBytes() }.getOrNull()?.let { return@withContext it }
    val picture = listOf(choice, PresetPlaceholder.Picture(preset.pictureIndex)).distinct().firstNotNullOfOrNull { c ->
        placeholderScene(context, preset, c)?.let { renderPresetPicture(engine, it) }
    } ?: return@withContext null
    keepCached(cached, picture)
    picture
}

fun addPresetBundle(context: Context, file: File): String {
    val projects = ProjectStore(context)
    return PresetStore(context).addBundle(projects, Importer.forApp(context, projects), TemplateStore(context)::localize) { file.inputStream() }
}

internal suspend fun addPresetBundle(context: Context, file: File, review: suspend (List<ImportReport>, ProjectStore) -> Unit): String {
    val projects = ProjectStore(context)
    val reports = withContext(Dispatchers.IO) { Importer.forApp(context, projects).import { file.inputStream() } }
    try {
        val report = reports.singleOrNull() ?: throw IOException("Cannot add the preset")
        review(reports, projects)
        return withContext(Dispatchers.IO) { PresetStore(context).adopt(projects, report.id, TemplateStore(context)::localize) }
            ?: throw IOException("Cannot add the preset")
    } catch (failure: Exception) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { reports.forEach { projects.delete(it.id) } }
        throw failure
    }
}

class PresetStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "presets"))

    fun list(): List<SavedPreset> = dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedByDescending { it.savedTime() }
        .mapNotNull { load(it.nameWithoutExtension) }

    fun load(id: String): SavedPreset? = runCatching { SavedPreset(id, Scene.parse(file(id).readText())) }.getOrNull()?.takeIf { it.layer != null }

    fun save(scene: Scene): String {
        val id = UUID.randomUUID().toString()
        write(id, scene)
        return id
    }

    fun rename(id: String, title: String) {
        val preset = load(id) ?: throw java.io.IOException("Cannot read preset $id")
        write(id, preset.scene.withTitle(title))
    }

    fun setPlaceholder(id: String, choice: PresetPlaceholder) {
        val preset = load(id) ?: throw IOException("Cannot read preset $id")
        write(id, preset.scene.withPlaceholder(choice))
    }

    fun delete(id: String) {
        file(id).delete()
        mediaDir(id).deleteRecursively()
    }

    fun deleteAll() {
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { delete(it.nameWithoutExtension) }
    }

    fun adopt(projects: ProjectStore, projectId: String, localize: (Scene, File) -> Scene): String? {
        val scene = projects.load(projectId)?.takeIf { it.layers.isNotEmpty() } ?: return null
        val id = UUID.randomUUID().toString()
        val stored = try { localize(scene, mediaDir(id)) } catch (e: IOException) { null }
        val left = projects.mediaDir(projectId).toURI().toString().trimEnd('/') + "/"
        if (stored == null || stored.node.storedMediaUris().any { it.startsWith(left) }) {
            mediaDir(id).deleteRecursively()
            return null
        }
        val placed = stored.layers.first().mediaFill?.let { stored.media(it.uri) }?.let { stored.withPlaceholder(PresetPlaceholder.Media(it)) } ?: stored
        write(id, Scene(placed.node.with("type", "preset")))
        projects.delete(projectId)
        return id
    }

    fun addBundle(projects: ProjectStore, importer: Importer, localize: (Scene, File) -> Scene, open: () -> InputStream?): String {
        val reports = importer.import(open)
        val id = reports.singleOrNull()?.let { adopt(projects, it.id, localize) }
        if (id == null) {
            reports.forEach { projects.delete(it.id) }
            throw IOException("Cannot add the preset")
        }
        return id
    }

    private fun mediaDir(id: String) = File(dir.parentFile, "media").child("preset-$id")

    private fun write(id: String, scene: Scene) = writeAtomically(file(id), scene.toXml())

    private fun file(id: String) = File(dir, "$id.xml")
}
