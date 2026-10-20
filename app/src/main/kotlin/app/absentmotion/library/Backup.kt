package app.absentmotion.library

import app.absentmotion.model.Scene
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

    data class Restored(val added: Int, val renamed: Int, val skipped: Int)

    fun files(root: File, includeMedia: Boolean): List<Pair<String, File>> {
        fun under(dir: String) = File(root, dir).walkTopDown().filter { it.isFile && it.extension != "tmp" && it.extension != "part" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
        val projects = under("projects")
        val (scenes, rest) = projects.partition { (name, _) -> name.count { it == '/' } == 1 && name.endsWith(".xml") }
        return scenes + rest + if (includeMedia) under("media") else emptyList()
    }

    fun write(root: File, out: OutputStream, includeMedia: Boolean, progress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val files = files(root, includeMedia)
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("$FORMAT\n${rootUri(root)}\n".toByteArray())
            zip.closeEntry()
            val buffer = ByteArray(64 * 1024)
            for ((name, file) in files) {
                zip.setLevel(if (name.startsWith("media/")) 0 else 6)
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { input ->
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

    fun restore(store: ProjectStore, root: File, input: InputStream, access: MediaAccess = MediaAccess.forPhone(root), progress: (read: Long) -> Unit = {}): Restored {
        val projects = File(root, "projects")
        val renamed = HashMap<String, String>()
        val skipped = HashSet<String>()
        val waiting = ArrayList<Pair<String, String>>()
        var added = 0
        var oldRoot = ""
        var read = 0L
        val counted = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) progress(++read) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { read += it; progress(read) } }
        }
        fun remap(text: String): String {
            val moved = if (oldRoot.isEmpty()) text else Regex(Regex.escape(oldRoot + "media/") + "([^/\"<>]+)/").replace(text) {
                rootUri(root) + "media/" + (renamed[it.groupValues[1]] ?: it.groupValues[1]) + "/"
            }
            return runCatching { Scene(Scene.parse(moved).node.mapMediaUris(access::defused)).toXml() }.getOrDefault(moved)
        }
        fun freshId(id: String): String =
            if (runCatching { UUID.fromString(id) }.isSuccess) UUID.randomUUID().toString()
            else generateSequence(System.currentTimeMillis()) { it + 1 }.map { "p$it" }.first { p -> !File(projects, "$p.xml").exists() && p !in renamed.values && waiting.none { it.first == p } }
        fun settle() {
            for ((id, text) in waiting) {
                val local = File(projects, "$id.xml")
                if (!local.exists()) continue
                if (runCatching { sameScene(local.readText(), remap(text)) }.getOrDefault(false)) skipped += id else renamed[id] = freshId(id)
            }
            for ((id, text) in waiting) if (id !in skipped) {
                store.save(renamed[id] ?: id, remap(text))
                added++
            }
            waiting.clear()
        }
        ZipInputStream(counted.buffered()).use { zip ->
            val first = zip.nextEntry
            val head = if (first?.name == MARKER) zip.readNBytes(4096).toString(Charsets.UTF_8).lines() else emptyList()
            if (head.firstOrNull() != FORMAT) throw ImportFailed("This file is not an AbsentMotion backup.")
            oldRoot = head.getOrNull(1).orEmpty().trim()
            while (true) {
                val entry = zip.nextEntry ?: break
                val parts = entry.name.replace('\\', '/').split('/')
                if (entry.isDirectory || parts.any { it.isEmpty() || it == "." || it == ".." }) continue
                val isScene = parts.size == 2 && parts[0] == "projects" && parts[1].endsWith(".xml")
                if (isScene) {
                    waiting += parts[1].removeSuffix(".xml") to readText(zip)
                    continue
                }
                if (waiting.isNotEmpty()) settle()
                val target = when {
                    parts.size == 2 && parts[0] == "projects" && parts[1].endsWith(".png") ->
                        parts[1].removeSuffix(".png").takeIf { it !in skipped }?.let { File(projects, (renamed[it] ?: it) + ".png") }
                    parts.size == 4 && parts[0] == "projects" && parts[1] == "versions" -> File(projects, "versions/${renamed[parts[2]] ?: parts[2]}/${parts[3]}")
                    parts.size >= 3 && parts[0] == "media" -> File(root, "media/${renamed[parts[1]] ?: parts[1]}/" + parts.drop(2).joinToString("/"))
                    else -> null
                } ?: continue
                if (target.exists()) continue
                if (target.extension == "xml") writeAtomically(target, remap(readText(zip)))
                else {
                    target.parentFile?.mkdirs()
                    val part = File(target.parentFile, target.name + ".part")
                    try {
                        part.outputStream().use { zip.copyTo(it) }
                        if (!part.renameTo(target)) part.delete()
                    } catch (t: Throwable) {
                        part.delete()
                        throw t
                    }
                }
            }
            if (waiting.isNotEmpty()) settle()
        }
        return Restored(added, renamed.size, skipped.size)
    }

    private fun readText(input: InputStream): String {
        val bytes = input.readNBytes(MAX_TEXT + 1)
        if (bytes.size > MAX_TEXT) throw ImportFailed("A project in the backup is too large.")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun rootUri(root: File) = root.toURI().toString().trimEnd('/') + "/"
}
