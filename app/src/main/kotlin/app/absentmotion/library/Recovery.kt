package app.absentmotion.library

import app.absentmotion.model.Scene
import java.io.File

data class RecoveryOffer(val id: String, val title: String, val xml: String)

class Recovery(private val dir: File) {
    fun file(id: String) = dir.child("$id.xml")

    @Synchronized
    fun write(id: String, xml: String) = writeAtomically(file(id), xml, sync = false)

    @Synchronized
    fun clear(id: String) {
        file(id).delete()
    }

    fun pending(store: ProjectStore): RecoveryOffer? {
        for (found in dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedByDescending { it.lastModified() }) {
            val id = found.nameWithoutExtension
            val xml = runCatching { found.readText() }.getOrNull()
            val scene = xml?.let { runCatching { Scene.parse(it) }.getOrNull() }
            if (xml == null || scene == null || !store.exists(id) || store.text(id)?.let { sameScene(it, xml) } == true) {
                clear(id)
                continue
            }
            return RecoveryOffer(id, scene.title, xml)
        }
        return null
    }

    fun restore(store: ProjectStore, offer: RecoveryOffer) {
        store.text(offer.id)?.let { store.versions.snapshot(offer.id, it) }
        store.save(offer.id, offer.xml)
        clear(offer.id)
    }

    fun discard(store: ProjectStore, offer: RecoveryOffer) {
        store.versions.snapshot(offer.id, offer.xml)
        clear(offer.id)
    }
}

internal const val StaleTempMs = 24 * 60 * 60 * 1000L
internal const val ImportMarker = "importing.part"

internal fun sweepStaleTemps(files: File, cache: File, now: Long = System.currentTimeMillis()): Int {
    var removed = 0
    fun old(f: File) = now - f.lastModified() > StaleTempMs
    File(files, "media").listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
        val marker = File(dir, ImportMarker)
        if (marker.isFile && old(marker)) {
            if (File(files, "projects/${dir.name}.xml").exists()) marker.delete() else if (dir.deleteRecursively()) removed++
        }
    }
    for (name in listOf("projects", "recovery", "media", "templates", "presets", "fonts/imported")) {
        File(files, name).walkTopDown().maxDepth(4)
            .filter { it.isFile && (it.name.endsWith(".tmp") || it.name.endsWith(".part")) && old(it) }
            .forEach { if (it.delete()) removed++ }
    }
    cache.listFiles { f -> f.isFile && (f.name.startsWith("ai-") || f.name.startsWith("probe-")) && old(f) }.orEmpty().forEach { if (it.delete()) removed++ }
    return removed
}

class RecoveryHandler private constructor(private val recovery: Recovery, private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    @Volatile var current: () -> Pair<String, String>? = { null }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            current()?.let { (id, xml) -> recovery.write(id, xml) }
        } catch (_: Throwable) {
        }
        previous?.uncaughtException(thread, error)
    }

    companion object {
        fun install(recovery: Recovery, current: () -> Pair<String, String>?): RecoveryHandler {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            val handler = existing as? RecoveryHandler ?: RecoveryHandler(recovery, existing).also { Thread.setDefaultUncaughtExceptionHandler(it) }
            handler.current = current
            return handler
        }
    }
}
