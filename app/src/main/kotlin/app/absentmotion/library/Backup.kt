package app.absentmotion.library

import app.absentmotion.platform.readAtMost
import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {
    const val EXTENSION = "absbackup"
    private const val MARKER = "absentmotion-backup.txt"
    private const val FORMAT = "AbsentMotion backup 1"
    private const val MAX_TEXT = 64 shl 20
    private val VersionFile = Regex("[0-9]{1,19}\\.(?:xml|png|damaged)")

    data class Restored(val added: Int, val renamed: Int, val skipped: Int, val rejected: Int = 0)

    fun files(root: File, includeMedia: Boolean, ai: File? = null): List<Pair<String, File>> {
        fun under(base: File, prefix: String) = base.walkTopDown().filter { it.isFile && it.extension != "tmp" && it.extension != "part" }
            .map { prefix + it.relativeTo(base).invariantSeparatorsPath to it }.toList()
        fun under(dir: String) = under(File(root, dir), "$dir/")
        val projects = under("projects")
        val (scenes, rest) = projects.partition { (name, _) -> name.count { it == '/' } == 1 && name.endsWith(".xml") }
        val templates = under("templates").filter { (name, _) -> includeMedia || name.split('/').getOrNull(2) != "media" }
        val media = if (includeMedia) under("media") + under("fonts/imported") + (ai?.let { under(it, "ai/") } ?: emptyList()) else emptyList()
        return scenes + rest + under("presets") + templates + media
    }

    fun write(root: File, out: OutputStream, includeMedia: Boolean, ai: File? = null, progress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val files = files(root, includeMedia, ai)
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("$FORMAT\n${rootUri(root)}\n".toByteArray())
            zip.closeEntry()
            val buffer = ByteArray(64 * 1024)
            for ((name, file) in files) {
                val source = try { file.inputStream() } catch (e: java.io.FileNotFoundException) { continue }
                zip.setLevel(if (name.endsWith(".xml")) 6 else 0)
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                source.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    fun restore(store: ProjectStore, root: File, input: InputStream, access: MediaAccess = MediaAccess.forPhone(root), ai: File? = null, progress: (read: Long) -> Unit = {}): Restored {
        val projects = File(root, "projects")
        val renamed = HashMap<String, String>()
        val skipped = HashSet<String>()
        val rejected = HashSet<String>()
        val waiting = ArrayList<Pair<String, String>>()
        val ready = ArrayList<Pair<String, String>>()
        val created = ArrayList<File>()
        val stamps = HashMap<String, Long>()
        var added = 0
        var oldRoot = ""
        var read = 0L
        val counted = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) progress(++read) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { read += it; progress(read) } }
        }
        fun moved(text: String): String = if (oldRoot.isEmpty()) text else Regex(Regex.escape(oldRoot) + "(?:media/([^/\"<>]+)/|templates/)").replace(text) {
            val id = it.groupValues[1]
            rootUri(root) + if (id.isEmpty()) "templates/" else "media/" + (renamed[id] ?: id) + "/"
        }
        fun remap(text: String, defuse: Boolean = true): String {
            val moved = moved(text)
            val node = runCatching { Scene.parse(moved).node }.getOrNull() ?: return moved
            val links = renamed.entries.associate { (old, new) -> store.linkOf(old) to store.linkOf(new) }
            val defused = (if (defuse) node.mapMediaUris(access::defused) else node).mapLinks { links[it] ?: it }
            return if (defused == node) moved else Scene(defused).toXml()
        }
        fun freshId(id: String): String =
            if (runCatching { UUID.fromString(id) }.isSuccess) UUID.randomUUID().toString()
            else generateSequence(System.currentTimeMillis()) { it + 1 }.map { "p$it" }.first { p ->
                !File(projects, "$p.xml").exists() && p !in renamed.values && waiting.none { it.first == p } && ready.none { it.first == p }
            }
        fun settle() {
            val same = ArrayList<Pair<String, String>>()
            for ((id, text) in waiting) {
                val local = File(projects, "$id.xml")
                if (local.exists()) {
                    if (runCatching { sameScene(local.readText(), moved(text)) }.getOrDefault(false)) { same += id to text; continue }
                    renamed[id] = freshId(id)
                }
                ready += id to text
            }
            do {
                var changed = false
                val iterator = same.iterator()
                while (iterator.hasNext()) {
                    val (id, text) = iterator.next()
                    if (runCatching { sameScene(File(projects, "$id.xml").readText(), remap(text, defuse = false)) }.getOrDefault(false)) continue
                    renamed[id] = freshId(id)
                    ready += id to text
                    iterator.remove()
                    changed = true
                }
            } while (changed)
            skipped += same.map { it.first }
            waiting.clear()
        }
        fun makeDirs(dir: File) {
            val missing = generateSequence(dir) { it.parentFile }.takeWhile { !it.exists() }.toList()
            dir.mkdirs()
            created += missing.asReversed()
        }
        fun publish() {
            for ((id, text) in ready) {
                val target = renamed[id] ?: id
                val file = File(projects, "$target.xml").also { created += it }
                store.save(target, remap(text))
                stamps[id]?.let { file.setLastModified(it) }
                added++
            }
            ready.clear()
        }
        try {
            ZipInputStream(counted.buffered()).use { zip ->
                val first = zip.nextEntry
                val head = if (first?.name == MARKER) zip.readAtMost(4096).toString(Charsets.UTF_8).lines() else emptyList()
                if (head.firstOrNull() != FORMAT) throw ImportFailed(txt(R.string.library_err_not_backup))
                oldRoot = head.getOrNull(1).orEmpty().trim()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val parts = entry.name.replace('\\', '/').split('/')
                    if (entry.isDirectory || !parts.all(::safeSegment)) continue
                    val isScene = parts.size == 2 && parts[0] == "projects" && parts[1].endsWith(".xml")
                    if (isScene) {
                        val id = parts[1].removeSuffix(".xml")
                        val text = readText(zip)
                        if (!ProjectId.matches(id) || runCatching { XmlNode.parse(text.removePrefix("\uFEFF")) }.getOrNull()?.let(Importer::checkScene) != null) rejected += id
                        else {
                            waiting += id to text
                            if (entry.time > 0) stamps[id] = entry.time
                        }
                        continue
                    }
                    if (waiting.isNotEmpty()) settle()
                    fun known(id: String) = id.takeIf { ProjectId.matches(it) && it !in rejected }
                    val target = when {
                        parts.size == 2 && parts[0] == "projects" && parts[1].endsWith(".png") ->
                            known(parts[1].removeSuffix(".png"))?.takeIf { it !in skipped }?.let { File(projects, (renamed[it] ?: it) + ".png") }
                        parts.size == 4 && parts[0] == "projects" && parts[1] == "versions" && VersionFile.matches(parts[3]) ->
                            known(parts[2])?.let { File(projects, "versions/${renamed[it] ?: it}/${parts[3]}") }
                        parts.size >= 3 && parts[0] == "media" -> known(parts[1])?.let { File(root, "media/${renamed[it] ?: it}/" + parts.drop(2).joinToString("/")) }
                        parts.size >= 3 && parts[0] == "templates" -> known(parts[1])?.let { File(root, parts.joinToString("/")) }
                        parts.size == 2 && parts[0] == "presets" && parts[1].endsWith(".xml") -> File(root, "presets/${parts[1]}")
                        parts.size == 3 && parts[0] == "fonts" && parts[1] == "imported" -> File(root, "fonts/imported/${parts[2]}")
                        parts.size == 2 && parts[0] == "ai" -> ai?.let { File(it, parts[1]) }
                        else -> null
                    } ?: continue
                    if (target.exists()) continue
                    makeDirs(target.parentFile!!)
                    val text = target.extension == "xml" && when (parts[0]) { "media" -> false; "templates" -> parts.size == 3; else -> true }
                    if (text) {
                        created += target
                        writeAtomically(target, remap(readText(zip)))
                    } else {
                        val part = File(target.parentFile, target.name + ".part")
                        try {
                            part.outputStream().use { zip.copyTo(it) }
                            if (!part.renameTo(target)) throw java.io.IOException("Cannot restore ${target.name}")
                            created += target
                        } catch (t: Throwable) {
                            part.delete()
                            throw t
                        }
                    }
                }
                if (waiting.isNotEmpty()) settle()
                publish()
            }
        } catch (t: Throwable) {
            created.asReversed().forEach { it.delete() }
            throw t
        }
        return Restored(added, renamed.size, skipped.size, rejected.size)
    }

    private fun readText(input: InputStream): String {
        val bytes = input.readAtMost(MAX_TEXT + 1)
        if (bytes.size > MAX_TEXT) throw ImportFailed(txt(R.string.library_err_backup_project_large))
        return bytes.toString(Charsets.UTF_8)
    }

    private fun rootUri(root: File) = root.toURI().toString().trimEnd('/') + "/"

    private fun XmlNode.mapLinks(change: (String) -> String): XmlNode =
        (this["link"]?.let { with("link", change(it)) } ?: this).let { it.copy(children = it.children.map { child -> child.mapLinks(change) }) }
}
