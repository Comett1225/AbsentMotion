package app.absentmotion.desk

import android.content.Context
import android.net.Uri
import app.absentmotion.audio.DeviceAudio
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object DeskSounds {
    private val SOUNDS = setOf("mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "oga", "opus", "aif", "aiff", "caf", "wma", "amr", "mka", "weba")
    private val PACKAGES = setOf("app", "band", "logicx", "photoslibrary", "musiclibrary", "tvlibrary", "bundle", "framework", "plugin", "component")
    private const val LIMIT = 2000
    private const val DEPTH = 8

    fun list(context: Context): List<DeviceAudio> =
        scan(DeskLibrary.files(SOUNDS), File(System.getProperty("user.home")), File(context.cacheDir, "device-sounds.json"))

    @Synchronized fun scan(files: List<File>, home: File, cacheFile: File): List<DeviceAudio> {
        val cache = runCatching { JSONObject(cacheFile.readText()) }.getOrElse { JSONObject() }
        val fresh = JSONObject()
        val stale = files.filter { f -> cache.optJSONObject(f.path)?.optString("stamp") != stamp(f) }
        val probed = if (stale.isEmpty()) emptyMap() else {
            val pool = Executors.newFixedThreadPool(4)
            try { stale.associateWith { f -> pool.submit<DeskProbe?> { runCatching { DeskProbe.of(f) }.getOrNull() } }.mapValues { it.value.get() } } finally { pool.shutdown() }
        }
        val found = files.mapNotNull { f ->
            val entry = probed[f]?.let { p ->
                JSONObject().put("stamp", stamp(f)).put("ms", if (p.audio != null) p.durationUs / 1000 else 0L)
                    .put("title", p.tags["title"].orEmpty()).put("artist", p.tags["artist"].orEmpty()).put("album", p.tags["album"].orEmpty())
            } ?: cache.optJSONObject(f.path)?.takeIf { f !in probed }
            entry ?: return@mapNotNull null
            fresh.put(f.path, entry)
            val ms = entry.optLong("ms")
            if (ms <= 0) return@mapNotNull null
            DeviceAudio(Uri.fromFile(f).toString(), entry.optString("title").ifBlank { f.nameWithoutExtension }, ms, f.lastModified() / 1000, f.name,
                entry.optString("album"), entry.optString("artist"), (f.parentFile?.relativeToOrNull(home)?.path ?: f.parent.orEmpty()).replace('\\', '/'))
        }
        if (stale.isNotEmpty() || fresh.length() != cache.length()) runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(fresh.toString()) }
        return found.sortedByDescending { it.date }
    }

    private fun stamp(f: File) = "${f.length()}:${f.lastModified()}"
}
