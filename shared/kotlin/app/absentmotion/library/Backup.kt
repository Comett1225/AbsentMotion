package app.absentmotion.library

import app.absentmotion.platform.drain
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
    private const val MAX_ENTRIES = 100_000
    private const val UNPACKED_HEADROOM = 64L shl 20
    private const val UNPACKED_RATIO = 200L
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

    fun write(root: File, out: OutputStream, includeMedia: Boolean, ai: File? = null, progress: (done: Long, total: Long) -> Unit = { _, _ -> }) =
        writeFiles(root, files(root, includeMedia, ai), out, progress)

    internal fun writeFiles(root: File, files: List<Pair<String, File>>, out: OutputStream, progress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("$FORMAT\n${rootUri(root)}\n".toByteArray())
            zip.closeEntry()
            val buffer = ByteArray(64 * 1024)
            for ((name, file) in files) {
                val entry = if (name.endsWith(".xml")) ZipEntry(name) else storedEntry(name, file.inputStream())
                val source = file.inputStream()
                zip.putNextEntry(entry.apply { time = file.lastModified() })
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
        val projects = File(root, "projects").apply { mkdirs() }
        val renamed = HashMap<String, String>()
        val skipped = HashSet<String>()
        val rejected = HashSet<String>()
        val scenes = HashSet<String>()
        val early = HashSet<String>()
        val waiting = ArrayList<Pair<String, File>>()
        val ready = ArrayList<Pair<String, File>>()
        val staged = ArrayList<File>()
        val created = ArrayList<File>()
        val stamps = HashMap<String, Long>()
        val fonts = HashMap<String, String>()
        val texts = ArrayList<File>()
        val staging = File(root, "restore-staging").also { it.deleteRecursively() }
        val held = HashMap<String, Boolean>()
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
            val defused = (if (defuse) node.mapMediaUris(access::defused) else node).mapLinks { links[it] ?: it }.mapFonts(fonts)
            return if (defused == node) moved else Scene(defused).toXml()
        }
        fun freshId(id: String): String =
            if (runCatching { UUID.fromString(id) }.isSuccess) UUID.randomUUID().toString()
            else generateSequence(System.currentTimeMillis()) { it + 1 }.map { "p$it" }.first { p ->
                !File(projects, "$p.xml").exists() && p !in renamed.values && waiting.none { it.first == p } && ready.none { it.first == p }
            }
        fun rename(id: String) {
            if (id in early) throw ImportFailed(txt(R.string.library_err_backup_changed))
            renamed[id] = freshId(id)
        }
        fun settle() {
            val same = ArrayList<Pair<String, File>>()
            for ((id, text) in waiting) {
                val local = File(projects, "$id.xml")
                if (local.exists()) {
                    if (runCatching { sameScene(local.readText(), moved(text.readText())) }.getOrDefault(false)) { same += id to text; continue }
                    rename(id)
                }
                ready += id to text
            }
            do {
                var changed = false
                val iterator = same.iterator()
                while (iterator.hasNext()) {
                    val (id, text) = iterator.next()
                    if (runCatching { sameScene(File(projects, "$id.xml").readText(), remap(text.readText(), defuse = false)) }.getOrDefault(false)) continue
                    rename(id)
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
                store.save(target, remap(text.readText()))
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
                var unpacked = 0L
                var entries = 0
                fun charge(n: Int) {
                    unpacked += n
                    val allowed = UNPACKED_HEADROOM + UNPACKED_RATIO * read
                    if (unpacked > allowed) throw ImportFailed(txt(R.string.library_err_package_large, (allowed shr 20).toString()))
                }
                val body = object : InputStream() {
                    override fun read(): Int = zip.read().also { if (it >= 0) charge(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { if (it > 0) charge(it) }
                }
                while (true) {
                    body.drain()
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw ImportFailed(txt(R.string.library_err_too_many_files, MAX_ENTRIES.toString()))
                    val parts = entry.name.replace('\\', '/').split('/')
                    if (entry.isDirectory || !parts.all(::safeSegment)) continue
                    val isScene = parts.size == 2 && parts[0] == "projects" && parts[1].endsWith(".xml")
                    if (isScene) {
                        val id = parts[1].removeSuffix(".xml")
                        if (!scenes.add(id)) throw ImportFailed(txt(R.string.library_err_backup_changed))
                        val text = readText(body)
                        if (!ProjectId.matches(id) || runCatching { XmlNode.parse(text.removePrefix("\uFEFF"), XmlNode.IMPORT_NODES) }.getOrNull().let { it == null || Importer.checkScene(it) != null }) rejected += id
                        else {
                            val file = File.createTempFile("restore-", ".part", projects).also { staged += it }
                            file.writeText(text)
                            waiting += id to file
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
                        parts.size >= 3 && parts[0] == "templates" -> known(parts[1])?.let { id ->
                            if (held.getOrPut(id) { File(root, "templates/$id/scene.xml").exists() }) File(staging, parts.drop(1).joinToString("/"))
                            else File(root, parts.joinToString("/"))
                        }
                        parts.size == 2 && parts[0] == "presets" && parts[1].endsWith(".xml") -> File(root, "presets/${parts[1]}")
                        parts.size == 3 && parts[0] == "fonts" && parts[1] == "imported" -> File(root, "fonts/imported/${parts[2]}")
                        parts.size == 2 && parts[0] == "ai" -> ai?.let { File(it, parts[1]) }
                        else -> null
                    } ?: continue
                    when {
                        parts[0] == "media" -> parts[1]
                        parts[0] == "projects" && parts[1] == "versions" -> parts[2]
                        parts[0] == "projects" -> parts[1].removeSuffix(".png")
                        else -> null
                    }?.takeIf { it !in scenes }?.let(early::add)
                    val font = parts[0] == "fonts"
                    val preset = parts[0] == "presets"
                    if (target.exists() && !font && !preset) continue
                    makeDirs(target.parentFile!!)
                    val text = target.extension == "xml" && when (parts[0]) { "media" -> false; "templates" -> parts.size == 3; else -> true }
                    if (text) {
                        val content = remap(readText(body))
                        var place = target
                        if (preset && target.exists()) {
                            if (target.parentFile!!.listFiles { f -> f.extension == "xml" }.orEmpty().any { f -> runCatching { sameScene(f.readText(), content) }.getOrDefault(false) }) continue
                            place = generateSequence { File(target.parentFile, "${UUID.randomUUID()}.xml") }.first { !it.exists() }
                        }
                        created += place
                        texts += place
                        writeAtomically(place, content)
                    } else {
                        val part = File(target.parentFile, target.name + ".part")
                        try {
                            part.outputStream().use { body.copyTo(it); it.fd.sync() }
                            var place = target
                            if (font && target.exists()) {
                                if (target.length() == part.length() && target.readBytes().contentEquals(part.readBytes())) {
                                    part.delete()
                                    continue
                                }
                                place = generateSequence(1) { it + 1 }.map { File(target.parentFile, "${target.nameWithoutExtension}-$it.${target.extension}") }.first { !it.exists() }
                                fonts["imported?name=${target.name}"] = "imported?name=${place.name}"
                            }
                            if (!part.renameTo(place)) throw java.io.IOException("Cannot restore ${place.name}")
                            created += place
                        } catch (t: Throwable) {
                            part.delete()
                            throw t
                        }
                    }
                }
                if (waiting.isNotEmpty()) settle()
                if (fonts.isNotEmpty()) for (file in texts) {
                    val node = runCatching { Scene.parse(file.readText()).node }.getOrNull() ?: continue
                    val changed = node.mapFonts(fonts)
                    if (changed != node) writeAtomically(file, Scene(changed).toXml())
                }
                for ((id, aside) in held) if (aside) {
                    val dir = File(staging, id)
                    val incoming = File(dir, "scene.xml")
                    if (!incoming.exists()) continue
                    val local = File(root, "templates/$id")
                    val files = dir.walkTopDown().filter { it.isFile && it != incoming }.toList()
                    if (runCatching { sameScene(File(local, "scene.xml").readText(), incoming.readText()) }.getOrDefault(false)) {
                        for (file in files) {
                            val target = File(local, file.relativeTo(dir).path)
                            if (target.exists()) continue
                            makeDirs(target.parentFile!!)
                            if (file.renameTo(target)) created += target
                        }
                        continue
                    }
                    val fresh = generateSequence(System.currentTimeMillis()) { it + 1 }.map { "t$it" }.first { !File(root, "templates/$it").exists() }
                    val place = File(root, "templates/$fresh")
                    var text = incoming.readText()
                    for (file in files) {
                        val rel = file.relativeTo(dir).invariantSeparatorsPath
                        text = text.replace("\"" + File(root, "templates/$id/$rel").toURI() + "\"", "\"" + File(place, rel).toURI() + "\"")
                    }
                    writeAtomically(incoming, text)
                    place.parentFile!!.mkdirs()
                    if (!dir.renameTo(place)) throw java.io.IOException("Cannot restore template $id")
                    place.walkTopDown().forEach { created += it }
                }
                publish()
            }
        } catch (t: Throwable) {
            created.asReversed().forEach { it.delete() }
            throw when (t) {
                is OutOfMemoryError -> ImportFailed(txt(R.string.library_err_backup_project_large))
                is java.util.zip.ZipException, is java.io.EOFException -> ImportFailed(txt(R.string.library_err_backup_damaged))
                else -> t
            }
        } finally {
            staged.forEach { it.delete() }
            staging.deleteRecursively()
        }
        return Restored(added, renamed.size, skipped.size, rejected.size)
    }

    private fun readText(input: InputStream): String {
        val bytes = input.readAtMost(MAX_TEXT + 1)
        if (bytes.size > MAX_TEXT) throw ImportFailed(txt(R.string.library_err_backup_project_large))
        return bytes.toString(Charsets.UTF_8)
    }

    private fun rootUri(root: File) = root.toURI().toString().trimEnd('/') + "/"

    private fun storedEntry(name: String, input: InputStream): ZipEntry {
        val crc = java.util.zip.CRC32()
        var size = 0L
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return ZipEntry(name).apply { method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc.value }
    }

    private fun XmlNode.mapLinks(change: (String) -> String): XmlNode =
        (this["link"]?.let { with("link", change(it)) } ?: this).let { it.copy(children = it.children.map { child -> child.mapLinks(change) }) }
}
