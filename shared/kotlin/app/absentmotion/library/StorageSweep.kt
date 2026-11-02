package app.absentmotion.library

import app.absentmotion.model.XmlNode
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder

internal fun sweepStorage(files: File, shared: List<File>, now: Long = System.currentTimeMillis()): Int {
    fun old(f: File) = f.walkTopDown().all { now - it.lastModified() > StaleTempMs }
    val projects = File(files, "projects")
    val versionsRoot = File(projects, "versions")
    val versions = ProjectVersions(versionsRoot, now = { now })
    var removed = 0
    for (dir in versionsRoot.listFiles { f -> f.isDirectory && safeSegment(f.name) }.orEmpty()) {
        if (File(projects, "${dir.name}.xml").exists()) versions.prune(dir.name)
        else if (old(dir) && dir.deleteRecursively()) removed++
    }
    for (dir in File(files, "templates").listFiles { f -> f.isDirectory }.orEmpty()) {
        if (!File(dir, "scene.xml").exists() && old(dir) && dir.deleteRecursively()) removed++
    }
    val owners = File(files, "media").listFiles { f -> f.isDirectory && !File(f, ImportMarker).exists() }.orEmpty().toList() +
        File(files, "templates").listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "media") }.filter { it.isDirectory }
    val candidates = (owners.flatMap { it.listFiles().orEmpty().toList() } + shared.flatMap { it.listFiles { f -> f.isFile }.orEmpty().toList() })
        .filter(::old).toMutableList()
    if (candidates.isEmpty()) return removed
    val paths = HashSet<String>()
    val names = HashSet<String>()
    val canonical = HashMap<String, String?>()
    val texts = projects.listFiles { f -> f.extension == "xml" }.orEmpty().asSequence() +
        versionsRoot.listFiles { f -> f.isDirectory }.orEmpty().asSequence().flatMap { it.listFiles { f -> f.extension == "xml" || f.extension == "damaged" }.orEmpty().asSequence() } +
        File(files, "recovery").listFiles { f -> f.extension == "xml" }.orEmpty().asSequence() +
        File(files, "templates").listFiles { f -> f.isDirectory }.orEmpty().asSequence().map { File(it, "scene.xml") }.filter { it.isFile } +
        File(files, "presets").listFiles { f -> f.extension == "xml" }.orEmpty().asSequence()
    for (source in texts) {
        val text = try { source.readText() } catch (e: Exception) { return removed } catch (e: OutOfMemoryError) { return removed }
        val uris = try { XmlNode.parse(text).keptUris() } catch (e: OutOfMemoryError) { return removed } catch (e: Exception) { null }
        if (uris == null) {
            candidates.removeAll { f -> listOf(f.name, f.toURI().rawPath.trimEnd('/').substringAfterLast('/')).any { it.isNotEmpty() && (text.contains(it) || text.contains(it.replace("&", "&amp;"))) } }
            continue
        }
        for (uri in uris) {
            names += runCatching { URLDecoder.decode(uri.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrDefault(uri.substringAfterLast('/'))
            if (uri.startsWith("file:", ignoreCase = true)) canonical.getOrPut(uri) { runCatching { File(URI(uri)).canonicalPath }.getOrNull() }?.let(paths::add)
        }
    }
    for (f in candidates) {
        val path = runCatching { f.canonicalPath }.getOrNull() ?: continue
        val used = f.name in names || path in paths || f.isDirectory && paths.any { it.startsWith(path + File.separator) }
        if (!used && f.deleteRecursively()) removed++
    }
    for (owner in owners) {
        if (owner.parentFile?.name == "media" && !File(projects, "${owner.name}.xml").exists() && owner.list()?.isEmpty() == true && owner.delete()) removed++
    }
    return removed
}

suspend fun sweepStorageDaily(context: Context) = withContext(Dispatchers.IO) {
    val stamp = File(context.noBackupFilesDir, "storage-sweep")
    val now = System.currentTimeMillis()
    if (now - stamp.lastModified() in 0 until StaleTempMs) return@withContext
    runCatching { stamp.writeText("") }
    val external = context.getExternalFilesDir(null)
    runCatching { sweepStorage(context.filesDir, listOfNotNull(external?.resolve("ai"), external?.resolve("gif")), now) }
        .onSuccess { Log.i("AMStore", "storage clean-up removed $it unused files") }
        .onFailure { Log.w("AMStore", "storage clean-up failed", it) }
}
