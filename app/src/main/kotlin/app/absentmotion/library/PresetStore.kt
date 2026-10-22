package app.absentmotion.library

import app.absentmotion.platform.Context
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import java.io.File
import java.util.UUID

data class SavedPreset(val id: String, val scene: Scene) {
    val title: String get() = scene.title
    val layer: Layer? get() = scene.layers.firstOrNull()
}

class PresetStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "presets"))

    fun list(): List<SavedPreset> = dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedByDescending { it.lastModified() }
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

    fun delete(id: String) {
        file(id).delete()
    }

    fun deleteAll() {
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { it.delete() }
    }

    private fun write(id: String, scene: Scene) = writeAtomically(file(id), scene.toXml())

    private fun file(id: String) = File(dir, "$id.xml")
}
