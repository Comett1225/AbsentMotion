package app.absentmotion.pc.media

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import app.absentmotion.pc.platform.DesktopPlatform
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

fun openProject(source: File, dataDir: File = DesktopPlatform.dataDir): Pair<Scene, File> {
    val zip = source.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
    if (!zip) return Scene.parse(source.readText().removePrefix("\uFEFF")) to source
    val root = dataDir.resolve("projects").also { check(it.isDirectory || it.mkdirs()) }
    val folder = java.nio.file.Files.createTempDirectory(root.toPath(), "import-").toFile()
    val installed = mutableListOf<File>()
    try {
        ZipFile(source).use { archive ->
            val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
            require(entries.size <= 2000) { "Too many package files" }
            val names = entries.associateBy { it.name.replace('\\', '/') }
            require(names.size == entries.size) { "Duplicate package files" }
            var remaining = 2L * 1024 * 1024 * 1024
            fun read(name: String, limit: Long, target: File) {
                val entry = names[name] ?: error("Missing package file: $name")
                require(entry.size < 0 || entry.size <= limit) { "Package file is too large" }
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        remaining -= n
                        require(size <= limit && remaining >= 0) { "Package is too large" }
                        output.write(buffer, 0, n)
                    }
                } }
            }
            val manifestEntry = entries.firstOrNull { it.name.substringAfterLast('/') == "manifest.json" } ?: error("Missing package manifest")
            val manifestFile = folder.resolve("manifest.json")
            read(manifestEntry.name, 16L * 1024 * 1024, manifestFile)
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.optString("format") == "absentmotion-package") { "Unsupported package" }
            val projectFile = folder.resolve("project.xml")
            read(manifest.getString("project"), 16L * 1024 * 1024, projectFile)
            val scene = Scene.parse(projectFile.readText().removePrefix("\uFEFF"))
            require(scene.node.name == "scene" && scene.node["width"]?.toIntOrNull() in 1..16384 && scene.node["height"]?.toIntOrNull() in 1..16384) { "Invalid project" }
            val moved = mutableMapOf<String, String>()
            val media = manifest.optJSONArray("media")
            if (media != null) for (i in 0 until media.length()) {
                val item = media.getJSONObject(i)
                val entry = item.optString("entry").takeIf { it.isNotBlank() } ?: continue
                val name = entry.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() && it != "." && it != ".." } ?: error("Invalid media name")
                val dir = folder.resolve("media/${i + 1}")
                val target = dir.resolve(name)
                read(entry, remaining, target)
                val companions = item.optJSONArray("companions")
                if (companions != null) for (j in 0 until companions.length()) {
                    val part = companions.getString(j)
                    val leaf = part.substringAfterLast('/').substringAfterLast('\\')
                    require(leaf.isNotBlank() && leaf != "." && leaf != ".." && leaf != name) { "Invalid companion name" }
                    val dest = dir.resolve(leaf)
                    require(!dest.exists()) { "Duplicate companion name" }
                    read(part, remaining, dest)
                }
                moved[item.getString("uri")] = target.toURI().toString()
            }
            val renamed = mutableMapOf<String, String>()
            val fonts = manifest.optJSONArray("fonts")
            if (fonts != null) for (i in 0 until fonts.length()) {
                val item = fonts.getJSONObject(i)
                val font = item.optString("font")
                if (!font.startsWith("imported?")) continue
                val name = font.substringAfter("name=", "").substringBefore('&').substringAfterLast('/').substringAfterLast('\\')
                require(name.isNotBlank() && name != "." && name != "..") { "Invalid font name" }
                val temp = folder.resolve("font-$i")
                read(item.getString("entry"), 32L * 1024 * 1024, temp)
                val bytes = temp.readBytes()
                require(bytes.size >= 4 && (bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 1, 0, 0)) || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) in setOf("OTTO", "ttcf", "true"))) { "Invalid font" }
                val fontDir = dataDir.resolve("fonts/imported").also { check(it.isDirectory || it.mkdirs()) }
                val target = generateSequence(0) { it + 1 }.map { index -> fontDir.resolve(if (index == 0) name else "${name.substringBeforeLast('.', name)}-$index.${name.substringAfterLast('.', "ttf")}") }.first { !it.exists() || it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
                if (!target.exists()) { temp.copyTo(target); installed += target }
                renamed[font] = "imported?name=${target.name}"
                temp.delete()
            }
            fun fontsOf(node: XmlNode): XmlNode {
                val next = node["font"]?.let(renamed::get)?.let { node.with("font", it) } ?: node
                return next.copy(children = next.children.map(::fontsOf))
            }
            val result = Scene(fontsOf(scene.node.mapMediaUris { moved[it] ?: it }))
            projectFile.writeText(result.toXml())
            manifestFile.delete()
            return result to projectFile
        }
    } catch (error: Throwable) {
        folder.deleteRecursively()
        installed.forEach { it.delete() }
        throw error
    }
}
