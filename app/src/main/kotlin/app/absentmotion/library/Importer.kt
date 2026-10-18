package app.absentmotion.library

import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaUris
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Serializable
import java.util.zip.ZipInputStream

class ImportFailed(message: String) : Exception(message)

data class ImportReport(
    val id: String,
    val title: String,
    val element: Boolean,
    val sections: List<Pair<String, List<String>>>,
    val template: Boolean = false,
) : Serializable

data class ImportLimits(
    val entries: Int = 2_000,
    val scenes: Int = 32,
    val unpacked: Long = 2L shl 30,
    val sceneText: Int = 64 shl 20,
)

class Importer(
    private val store: ProjectStore,
    private val exists: (String) -> Boolean = { true },
    private val canRun: (String) -> Boolean = { true },
    private val hasFont: (String) -> Boolean = { true },
    private val relink: (Scene) -> Scene = { it },
    private val access: MediaAccess = MediaAccess.Unlimited,
    private val limits: ImportLimits = ImportLimits(),
) {
    fun import(open: () -> InputStream?): List<ImportReport> {
        fun stream() = open() ?: throw ImportFailed("No data received.")
        val head = stream().use { it.readNBytes(2) }
        if (head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return fromPackage(::stream).map { if (it.element) it else it.copy(template = true) }
        }
        val text = stream().use { readText(it, MaxXml) }.removePrefix("\uFEFF")
        if (!text.trimStart().startsWith("<")) throw ImportFailed("Unsupported file type")
        return listOf(add(text) { _, _ -> emptyMap() })
    }

    private fun fromPackage(open: () -> InputStream): List<ImportReport> {
        val scenes = ArrayList<String>()
        val files = HashSet<String>()
        var sigs: Map<String, String>? = null
        var links: Map<String, String>? = null
        var sceneText = 0
        var written = limits.unpacked
        try {
            eachEntry(open, limits) { name, input ->
                files += name
                when {
                    name == "manifest.txt" -> sigs = readText(input, MaxXml).lineSequence().map { it.trim().split(':', limit = 3) }.filter { it.size >= 2 }
                        .mapNotNull { p -> leaf(p.last())?.let { p[0] to it } }.toMap()
                    name == "manifest.json" -> links = JSONObject(readText(input, MaxXml)).optJSONArray("media")?.let { list ->
                        (0 until list.length()).map { list.getJSONObject(it) }.filter { it.has("entry") }.mapNotNull { m -> leaf(m.getString("entry"))?.let { m.getString("uri") to it } }.toMap()
                    }.orEmpty()
                    name.endsWith(".xml", ignoreCase = true) -> {
                        if (scenes.size >= limits.scenes) throw ImportFailed("The package holds too many projects (more than ${limits.scenes})")
                        val text = readText(input, MaxXml)
                        sceneText += text.length
                        if (sceneText > limits.sceneText) throw ImportFailed("The package's project files are too large")
                        scenes += text
                    }
                }
            }
            if (sigs == null && links == null) throw ImportFailed("Project package missing manifest")
            if (scenes.isEmpty()) throw ImportFailed("Malformed Scene: no project in the package")
            return scenes.map { text ->
                add(text) { id, root ->
                    val declared = Scene(root).media.associateBy { it.uri }
                    val entries = root.mediaUris().mapNotNull { uri ->
                        val entry = links?.get(uri) ?: declared[uri]?.node?.get("sig")?.let { sigs?.get(it) }
                            ?: uri.substringAfter(':').takeIf { uri.startsWith("amproj:") || uri.startsWith("am-docs:") }?.let(::leaf)
                        entry?.takeIf { it in files }?.let { uri to it }
                    }.toMap()
                    val target = entries.values.toSet().associateWith { File(store.mediaDir(id), it) }
                    if (target.isNotEmpty()) {
                        store.mediaDir(id).mkdirs()
                        eachEntry(open, limits) { name, input -> target[name]?.let { written -= copy(input, it, written) } }
                    }
                    entries.mapValues { (_, entry) -> target.getValue(entry).toURI().toString() }
                }
            }
        } catch (e: IOException) {
            throw ImportFailed("Malformed package: ${e.message}")
        }
    }

    private fun add(text: String, copies: (String, XmlNode) -> Map<String, String>): ImportReport {
        val root = try { XmlNode.parse(text.removePrefix("﻿")) } catch (e: Exception) { throw ImportFailed("Malformed XML: ${e.message}") }
        if (root.name != "scene" || (root["width"]?.toIntOrNull() ?: 0) <= 0 || (root["height"]?.toIntOrNull() ?: 0) <= 0) {
            throw ImportFailed("Malformed Scene: expected a <scene> with a size")
        }
        val id = store.newId()
        try {
            val moved = copies(id, root)
            var scene = relink(Scene(root.mapMediaUris { moved[it] ?: access.defused(it) }))
            if (scene.title.isBlank()) scene = scene.withTitle("Imported Project")
            val missing = scene.node.mediaUris().filterNot { access.allows(it) && exists(it) }
            for (uri in missing) scene = scene.withMedia(MediaDeclaration(XmlNode("media", mapOf("uri" to uri))))
            scene = Scene(scene.node.copy(children = scene.node.children.map { if (it.name == "media" && it["uri"] in missing) it.with("missing", "true") else it }))
            store.save(id, scene)
            return ImportReport(id, scene.title, scene.node["type"] == "element", report(scene, missing))
        } catch (t: Throwable) {
            store.mediaDir(id).deleteRecursively()
            throw t
        }
    }

    private fun report(scene: Scene, missing: List<String>): List<Pair<String, List<String>>> {
        val layers = allLayers(scene.node).toList()
        val unknown = sortedSetOf<String>()
        scene.node.children.filter { it.name !in Scene.LAYER_TAGS && it.name != "media" && it.name != "bookmark" }.forEach { unknown += "<${it.name}>" }
        for (layer in layers) for (attr in layer.attrs.keys - KnownAttributes) unknown += "${layer.name} $attr"
        val effects = layers.flatMap { l -> l.children.filter { it.name == "effect" }.mapNotNull { it["id"] }.distinct() }
            .groupingBy { it }.eachCount().filterKeys { !canRun(it) }
            .map { (id, n) -> id.substringAfterLast('.') + if (n == 1) " (1 layer)" else " ($n layers)" }
        val fonts = layers.filter { it.name == "text" }.mapNotNull { it["font"] }.filter { it.startsWith("imported") && !hasFont(it) }
            .map { it.substringAfter("name=").substringBefore('&') }.distinct()
        val names = missing.map { uri -> scene.media(uri)?.filename?.takeIf { it.isNotEmpty() } ?: uri.substringAfterLast('/') }
        return listOf(
            "Not recognised, kept in the file" to unknown.toList(), "Effects this version cannot run" to effects,
            "Media not found" to names, "Fonts not installed" to fonts,
        ).filter { it.second.isNotEmpty() }
    }

    companion object {
        private const val MaxXml = 32 shl 20

        private val KnownAttributes = setOf(
            "id", "label", "startTime", "endTime", "fillType", "fillImage", "fillVideo", "inTime", "outTime", "speed", "blending",
            "parent", "hidden", "clippingMask", "mediaFillMode", "s", "tag", "align", "font", "size", "wrapWidth", "src", "type", "link",
            "presetId", "templatePPId", "points", "color", "stroke", "style",
        )

        private fun allLayers(scene: XmlNode): Sequence<XmlNode> = scene.children.asSequence().filter { it.name in Scene.LAYER_TAGS }
            .flatMap { sequenceOf(it) + (it.child("scene")?.let(::allLayers) ?: emptySequence()) }

        private fun leaf(name: String): String? = name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotEmpty() && it != "." && it != ".." }

        private fun eachEntry(open: () -> InputStream, limits: ImportLimits, visit: (String, InputStream) -> Unit) {
            var left = limits.unpacked
            var entries = 0
            ZipInputStream(open().buffered()).use { zip ->
                fun charge(n: Int) {
                    left -= n
                    if (left < 0) throw ImportFailed("The package is too large (more than ${limits.unpacked shr 20} MB unpacked)")
                }
                val counted = object : InputStream() {
                    override fun read(): Int = zip.read().also { if (it >= 0) charge(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { if (it > 0) charge(it) }
                }
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.entries) throw ImportFailed("The package has too many files (more than ${limits.entries})")
                    if (!entry.isDirectory) leaf(entry.name)?.let { visit(it, counted) }
                    counted.transferTo(OutputStream.nullOutputStream())
                }
            }
        }

        private fun readText(input: InputStream, limit: Int): String {
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) throw ImportFailed("Malformed XML: file is too large")
            return bytes.toString(Charsets.UTF_8)
        }

        private fun copy(input: InputStream, target: File, allowed: Long): Long {
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            try {
                target.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > allowed) throw ImportFailed("The package is too large (more than ${allowed shr 20} MB of media)")
                        out.write(buffer, 0, n)
                    }
                }
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
            return total
        }

        fun forApp(context: Context, store: ProjectStore): Importer {
            val catalog = EffectCatalog.read(context)
            val fonts = FontLibrary(context)
            return Importer(
                store,
                access = MediaAccess.forApp(context),
                exists = { uri -> runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } }.getOrNull() == true },
                canRun = { catalog.find(it) != null || it.endsWith(".textspacing") },
                hasFont = { fonts.localFile(it) != null },
                relink = { MediaRelink.relink(context, it) },
            )
        }
    }
}
