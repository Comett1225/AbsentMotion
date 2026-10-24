package app.absentmotion.library

import app.absentmotion.platform.drain
import app.absentmotion.platform.readAtMost
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import app.absentmotion.compat.EffectMigration
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaUris
import app.absentmotion.model.storedMediaUris
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Serializable
import java.util.zip.ZipInputStream

class ImportFailed(val text: UiText) : Exception(text.toString())

fun reportText(value: String): UiText = txt(R.string.library_text_value, value)

data class ImportReport(
    val id: String,
    val title: String,
    val element: Boolean,
    val sections: List<Pair<UiText, List<UiText>>>,
    val template: Boolean = false,
) : Serializable

data class ImportLimits(
    val entries: Int = 2_000,
    val scenes: Int = 32,
    val unpacked: Long = 2L shl 30,
    val sceneText: Int = 32 shl 20,
)

class Importer(
    private val store: ProjectStore,
    private val exists: (String) -> Boolean = { true },
    private val canRun: (String) -> Boolean = { true },
    private val hasFont: (String) -> Boolean = { true },
    private val relink: (Scene) -> Scene = { it },
    private val migration: EffectMigration? = null,
    private val access: MediaAccess = MediaAccess.Unlimited,
    private val limits: ImportLimits = ImportLimits(),
) {
    fun import(open: () -> InputStream?): List<ImportReport> = try {
        fun stream() = open() ?: throw ImportFailed(txt(R.string.library_err_no_data))
        val head = stream().use { it.readAtMost(2) }
        if (head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) fromPackage(::stream)
        else {
            val text = stream().use { readText(it, MaxXml) }.removePrefix("\uFEFF")
            if (!text.trimStart().startsWith("<")) throw ImportFailed(txt(R.string.library_err_unsupported_type))
            listOf(add(store.newId(), check(text), emptyMap()))
        }
    } catch (e: OutOfMemoryError) {
        throw ImportFailed(txt(R.string.library_err_xml_large))
    }

    private fun fromPackage(open: () -> InputStream): List<ImportReport> {
        val scenes = ArrayList<String>()
        val files = LinkedHashSet<String>()
        var sigs: Map<String, String>? = null
        var links: Map<String, String>? = null
        var companions: Map<String, List<String>> = emptyMap()
        var projectEntry: String? = null
        var ours = false
        var sceneText = 0
        var written = limits.unpacked
        val ids = ArrayList<String>()
        val saved = ArrayList<ImportReport>()
        try {
            eachEntry(open, limits) { name, input ->
                files += name
                when {
                    leaf(name) == "manifest.txt" -> sigs = readText(input, MaxXml).lineSequence().map { it.trim().split(':', limit = 3) }.filter { it.size >= 2 }
                        .associate { p -> p[0] to p.last() }
                    leaf(name) == "manifest.json" -> {
                        val manifest = JSONObject(readText(input, MaxXml))
                        projectEntry = manifest.optString("project").takeIf { it.isNotBlank() }
                        ours = manifest.optString("format") == "absentmotion-package"
                        val listed = manifest.optJSONArray("media")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) }.filter { it.has("entry") } }.orEmpty()
                        links = listed.associate { m -> m.getString("uri") to m.getString("entry") }
                        companions = listed.filter { it.has("companions") }.associate { m ->
                            m.getString("uri") to m.getJSONArray("companions").let { c -> List(c.length()) { c.getString(it) } }
                        }
                    }
                }
            }
            if (sigs == null && links == null) throw ImportFailed(txt(R.string.library_err_missing_manifest))
            val declaredProject = projectEntry?.let { find(it, files) ?: throw ImportFailed(txt(R.string.library_err_package_project_missing)) }
            val mediaEntries = (links.orEmpty().values + sigs.orEmpty().values).mapNotNull { find(it, files) }.toSet()
            eachEntry(open, limits) { name, input ->
                if (name == declaredProject || declaredProject == null && name.endsWith(".xml", ignoreCase = true) && name !in mediaEntries) {
                    val text = readText(input, MaxXml)
                    val isScene = declaredProject != null || runCatching { XmlNode.parse(text.removePrefix("\uFEFF"), XmlNode.IMPORT_NODES).name == "scene" }.getOrDefault(false)
                    if (isScene) {
                        if (scenes.size >= limits.scenes) throw ImportFailed(txt(R.string.library_err_too_many_projects, limits.scenes.toString()))
                        sceneText += text.length
                        if (sceneText > limits.sceneText) throw ImportFailed(txt(R.string.library_err_project_files_large))
                        scenes += text
                    }
                }
            }
            if (scenes.isEmpty()) throw ImportFailed(txt(R.string.library_err_no_project))
            repeat(scenes.size) { ids += store.newId(ids) }
            ids.forEach { File(store.mediaDir(it), ImportMarker).apply { parentFile?.mkdirs(); createNewFile() } }
            val copies = HashMap<String, MutableList<File>>()
            val moved = scenes.mapIndexed { i, text ->
                val id = ids[i]
                val root = check(text)
                val declared = Scene(root).media.associateBy { it.uri }
                val entries = root.storedMediaUris().mapNotNull { uri ->
                    val entry = links?.get(uri) ?: declared[uri]?.node?.get("sig")?.let { sigs?.get(it) }
                        ?: uri.substringAfter(':').takeIf { uri.startsWith("amproj:") || uri.startsWith("am-docs:") }
                    entry?.let { find(it, files) }?.let { uri to it }
                }.toMap()
                val names = HashSet<String>()
                fun unique(name: String) = (sequenceOf(name) + generateSequence(2) { it + 1 }.map { "${it}_$name" }).first { names.add(it) }
                val target = HashMap<String, File>()
                for ((uri, path) in entries) {
                    if (path in target) continue
                    val parts = companions[uri]?.mapNotNull { find(it, files) }
                    if (parts == null) target[path] = File(store.mediaDir(id), unique(leaf(path)!!))
                    else {
                        val folder = File(store.mediaDir(id), generateSequence(1) { it + 1 }.map { "model-$it" }.first { names.add(it) })
                        (listOf(path) + parts).forEach { part -> target.putIfAbsent(part, File(folder, leaf(part)!!)) }
                    }
                }
                target.forEach { (path, file) -> copies.getOrPut(path) { ArrayList() } += file }
                entries.mapValues { (_, entry) -> target.getValue(entry).toURI().toString() }
            }
            if (copies.isNotEmpty()) {
                copies.values.flatten().forEach { it.parentFile?.mkdirs() }
                eachEntry(open, limits) { name, input ->
                    val targets = copies[name] ?: return@eachEntry
                    written -= copy(input, targets.first(), written)
                    for (other in targets.drop(1)) written -= targets.first().inputStream().use { copy(it, other, written) }
                }
            }
            scenes.indices.mapTo(saved) { add(ids[it], check(scenes[it]), moved[it]) }
            ids.forEach { File(store.mediaDir(it), ImportMarker).delete(); store.mediaDir(it).delete() }
            return if (ours) saved else saved.map { if (it.element) it else it.copy(template = true) }
        } catch (t: Throwable) {
            ids.forEach { store.mediaDir(it).deleteRecursively() }
            saved.forEach { store.delete(it.id) }
            if (t is IOException) throw ImportFailed(txt(R.string.library_err_malformed_package, t.message.toString()))
            throw t
        }
    }

    private fun check(text: String): XmlNode {
        val root = try { XmlNode.parse(text.removePrefix("\uFEFF"), XmlNode.IMPORT_NODES) } catch (e: Exception) { throw ImportFailed(txt(R.string.library_err_malformed_xml, e.message.toString())) }
        checkScene(root)?.let { throw ImportFailed(it) }
        return root
    }

    private fun add(id: String, root: XmlNode, moved: Map<String, String>): ImportReport {
        try {
            var scene = relink(Scene(uniqueLayerIds(root.mapMediaUris { moved[it] ?: access.imported(it) })))
            val migrated = migration?.migrate(scene)
            if (migrated != null) scene = migrated.scene
            if (scene.title.isBlank()) scene = scene.withTitle(AppText.get(R.string.library_imported_project))
            val missing = scene.node.mediaUris().filterNot { access.allows(it) && exists(it) }
            for (uri in missing) scene = scene.withMedia(MediaDeclaration(XmlNode("media", mapOf("uri" to uri))))
            scene = Scene(scene.node.copy(children = scene.node.children.map { if (it.name == "media" && it["uri"] in missing) it.with("missing", "true") else it }))
            store.save(id, scene)
            return ImportReport(id, scene.title, scene.node["type"] == "element", report(scene, missing, migrated))
        } catch (t: Throwable) {
            store.mediaDir(id).deleteRecursively()
            throw t
        }
    }

    private fun report(scene: Scene, missing: List<String>, migrated: EffectMigration.Result?): List<Pair<UiText, List<UiText>>> {
        val layers = allLayers(scene.node).toList()
        val unknown = sortedSetOf<String>()
        scene.node.children.filter { it.name !in Scene.LAYER_TAGS && it.name != "media" && it.name != "bookmark" }.forEach { unknown += "<${it.name}>" }
        for (layer in layers) for (attr in layer.attrs.keys - KnownAttributes) unknown += "${layer.name} $attr"
        val effects = layers.flatMap { l -> l.children.filter { it.name == "effect" }.mapNotNull { it["id"] }.distinct() }
            .groupingBy { it }.eachCount().filterKeys { !canRun(it) }
            .map { (id, n) -> UiText.plural(R.plurals.library_report_effect_layers, n, id.substringAfterLast('.'), n.toString()) }
        val fonts = layers.filter { it.name == "text" }.mapNotNull { it["font"] }.filter { it.startsWith("imported") && !hasFont(it) }
            .map { it.substringAfter("name=").substringBefore('&') }.distinct()
        val names = missing.map { uri -> scene.media(uri)?.filename?.takeIf { it.isNotEmpty() } ?: uri.substringAfterLast('/') }
        return listOf(
            txt(R.string.library_report_unknown) to unknown.map(::reportText), txt(R.string.library_report_cannot_run) to effects,
            txt(R.string.library_report_converted) to migrated?.converted?.map { (what, n) -> UiText.plural(R.plurals.library_report_converted_effects, n, what, n.toString()) }.orEmpty(),
            txt(R.string.library_report_dropped) to migrated?.dropped?.map(::reportText).orEmpty(),
            txt(R.string.library_report_media) to names.map(::reportText), txt(R.string.library_report_fonts) to fonts.map(::reportText),
        ).filter { it.second.isNotEmpty() }
    }

    companion object {
        private const val MaxXml = 16 shl 20
        private const val MaxSide = 16384
        private const val MaxFps = 240
        private const val MaxHours = 24

        internal fun checkScene(root: XmlNode): UiText? {
            val width = root["width"]?.toIntOrNull() ?: 0
            val height = root["height"]?.toIntOrNull() ?: 0
            return when {
                root.name != "scene" || width <= 0 || height <= 0 -> txt(R.string.library_err_scene_needs_size)
                width > MaxSide || height > MaxSide -> txt(R.string.library_err_scene_too_big, MaxSide.toString())
                root["fps"]?.toIntOrNull()?.let { it !in 1..MaxFps } == true -> txt(R.string.library_err_scene_fps, MaxFps.toString())
                root["totalTime"]?.toLongOrNull()?.let { it !in 0..MaxHours * 3_600_000L } == true -> txt(R.string.library_err_scene_long, MaxHours.toString())
                else -> null
            }
        }

        private val KnownAttributes = setOf(
            "id", "label", "startTime", "endTime", "fillType", "fillImage", "fillVideo", "inTime", "outTime", "speed", "blending",
            "parent", "hidden", "clippingMask", "mediaFillMode", "s", "tag", "align", "font", "size", "wrapWidth", "src", "type", "link",
            "presetId", "templatePPId", "points", "color", "stroke", "style",
        )

        private fun uniqueLayerIds(root: XmlNode): XmlNode {
            val taken = HashSet<Long>()
            fun collect(node: XmlNode) {
                if (node.name in Scene.LAYER_TAGS) node["id"]?.toLongOrNull()?.let(taken::add)
                node.children.forEach(::collect)
            }
            collect(root)
            val seen = HashSet<Long>()
            fun scene(node: XmlNode): XmlNode {
                val owners = HashMap<Long, Long>()
                val renumbered = node.children.map { child ->
                    if (child.name !in Scene.LAYER_TAGS) return@map child
                    val old = child["id"]?.toLongOrNull()?.takeIf { it != 0L }
                    val id = if (old != null && seen.add(old)) old else generateSequence { Layer.newId() }.first { taken.add(it) }
                    if (old != null) owners.putIfAbsent(old, id)
                    if (id == old) child else child.with("id", "$id")
                }
                return node.copy(children = renumbered.map { child ->
                    if (child.name !in Scene.LAYER_TAGS) return@map child
                    val parent = child["parent"]?.toLongOrNull()?.let(owners::get)
                    val linked = if (parent == null || child["parent"] == "$parent") child else child.with("parent", "$parent")
                    if (linked.name == "embedScene") linked.child("scene")?.let { linked.withChild("scene", scene(it)) } ?: linked else linked
                })
            }
            return scene(root)
        }

        private fun allLayers(scene: XmlNode): Sequence<XmlNode> = scene.children.asSequence().filter { it.name in Scene.LAYER_TAGS }
            .flatMap { sequenceOf(it) + (it.child("scene")?.let(::allLayers) ?: emptySequence()) }

        private fun leaf(name: String): String? = name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotEmpty() && it != "." && it != ".." }

        private fun entryPath(name: String) = name.replace('\\', '/').trimStart('/')

        private fun find(reference: String, paths: Collection<String>): String? {
            val path = entryPath(reference)
            val name = leaf(path) ?: return null
            return path.takeIf { it in paths } ?: paths.firstOrNull { it.endsWith("/$path") } ?: paths.firstOrNull { leaf(it) == name }
        }

        private fun eachEntry(open: () -> InputStream, limits: ImportLimits, visit: (String, InputStream) -> Unit) {
            var left = limits.unpacked
            var entries = 0
            ZipInputStream(open().buffered()).use { zip ->
                fun charge(n: Int) {
                    left -= n
                    if (left < 0) throw ImportFailed(txt(R.string.library_err_package_large, (limits.unpacked shr 20).toString()))
                }
                val counted = object : InputStream() {
                    override fun read(): Int = zip.read().also { if (it >= 0) charge(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { if (it > 0) charge(it) }
                }
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.entries) throw ImportFailed(txt(R.string.library_err_too_many_files, limits.entries.toString()))
                    if (!entry.isDirectory) entryPath(entry.name).takeIf { leaf(it) != null }?.let { visit(it, counted) }
                    counted.drain()
                }
            }
        }

        private fun readText(input: InputStream, limit: Int): String {
            val bytes = input.readAtMost(limit + 1)
            if (bytes.size > limit) throw ImportFailed(txt(R.string.library_err_xml_large))
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
                        if (total > allowed) throw ImportFailed(txt(R.string.library_err_media_large, (allowed shr 20).toString()))
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
                migration = EffectMigration.read(context, catalog),
            )
        }
    }
}
