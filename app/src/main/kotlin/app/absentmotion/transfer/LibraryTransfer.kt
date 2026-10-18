package app.absentmotion.transfer

import app.absentmotion.library.MediaAccess
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mediaUris
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class TransferEntry(val path: String, val size: Long, val sha256: String) {
    fun json() = JSONObject().put("path", path).put("size", size).put("sha256", sha256)
}
data class TransferManifest(val name: String, val entries: List<TransferEntry>, val media: Map<String, String>) {
    fun json() = JSONObject().put("version", 2).put("name", name).put("files", JSONArray(entries.map { it.json() }))
        .put("media", JSONObject(media))
    companion object {
        fun parse(json: JSONObject): TransferManifest {
            require(json.getInt("version") == 2) { "Unsupported transfer version" }
            val files = json.getJSONArray("files")
            require(files.length() <= 100_000)
            val entries = List(files.length()) { i -> files.getJSONObject(i).let {
                TransferEntry(it.getString("path"), it.getLong("size"), it.getString("sha256"))
            } }
            require(entries.map { it.path }.distinct().size == entries.size)
            val uris = json.getJSONObject("media")
            return TransferManifest(json.getString("name").take(100), entries, uris.keys().asSequence().associateWith { uris.getString(it) })
        }
    }
}
fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

class LibraryPack(val manifest: TransferManifest, private val files: Map<String, File>, val missing: List<String> = emptyList()) {
    fun open(path: String): InputStream = files.getValue(path).inputStream()
    companion object {
        fun collect(root: File, name: String, selected: Set<String>? = null, access: MediaAccess = MediaAccess.forPhone(root),
                    external: (String) -> File? = { uri -> runCatching { File(URI(uri)) }.getOrNull() }): LibraryPack {
            val files = linkedMapOf<String, File>()
            val projectIds = selected?.toMutableSet()
            if (projectIds != null) {
                val projects = File(root, "projects").listFiles { f -> f.extension == "xml" }.orEmpty()
                fun link(id: String) = runCatching { java.util.UUID.fromString(id).toString() }.getOrElse { java.util.UUID.nameUUIDFromBytes(id.toByteArray()).toString() }
                var changed: Boolean
                do {
                    val before = projectIds.size
                    val links = mutableSetOf<String>()
                    fun walk(node: XmlNode) { node["link"]?.let(links::add); node.children.forEach(::walk) }
                    projects.filter { it.nameWithoutExtension in projectIds }.forEach { file -> runCatching { walk(XmlNode.parse(file.readText())) } }
                    projects.filter { link(it.nameWithoutExtension) in links }.forEach { projectIds += it.nameWithoutExtension }
                    changed = before != projectIds.size
                } while (changed)
            }
            val directories = if (selected == null) listOf("projects", "fonts", "presets", "templates", "elements", "preferences")
                else listOf("projects", "fonts", "preferences")
            for (dir in directories) {
                File(root, dir).walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    if (dir != "projects" || projectIds == null || file.nameWithoutExtension in projectIds) files[path] = file
                }
            }
            val media = linkedMapOf<String, String>()
            val missing = mutableListOf<String>()
            val documents = files.filter { it.key.endsWith(".xml") }.values.toList()
            for (file in documents) {
                val node = runCatching { XmlNode.parse(file.readText()) }.getOrNull() ?: continue
                val filenames = mutableMapOf<String, String>()
                fun record(n: XmlNode) {
                    if (n.name == "media") n["uri"]?.let { filenames[it] = n["filename"].orEmpty() }
                    n.children.forEach(::record)
                }
                record(node)
                val uris = node.mediaUris() + node.children.filter { it.name == "media" }.mapNotNull { it["uri"] }
                for (uri in uris) if (uri !in media && uri !in missing) {
                    val source = uri.takeIf(access::allows)?.let(external)?.takeIf { it.isFile }
                    if (source == null) { missing += uri; continue }
                    val hash = sha256(source)
                    val suffix = source.extension.ifEmpty { File(filenames[uri].orEmpty()).extension }.lowercase().filter { it.isLetterOrDigit() }.take(12)
                    val path = "media/transfer/$hash" + if (suffix.isEmpty()) "" else ".$suffix"
                    files[path] = source
                    media[uri] = path
                }
            }
            return LibraryPack(TransferManifest(name, files.map { (path, file) -> TransferEntry(path, file.length(), sha256(file)) }, media), files, missing)
        }
    }
}

data class TransferAddress(val host: String, val port: Int, val token: String) {
    fun qr() = "absentmotion://transfer?host=$host&port=$port&token=$token"
    companion object {
        fun parse(text: String): TransferAddress {
            val uri = URI(text)
            require(uri.scheme == "absentmotion" && uri.host == "transfer") { "Scan the QR code in AbsentMotion on your Mac." }
            val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val host = q.getValue("host")
            require(host.matches(Regex("[0-9.]+"))) { "Use the same Wi-Fi or local network as your Mac." }
            val ip = InetAddress.getByName(host)
            require(ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress) { "Use the same Wi-Fi or local network as your Mac." }
            val port = q.getValue("port").toInt(); require(port in 1..65535)
            val token = q.getValue("token"); require(token.matches(Regex("[a-f0-9]{32}")))
            return TransferAddress(host, port, token)
        }
    }
}

class TransferSender(val address: TransferAddress) {
    private fun connection(path: String, method: String): HttpURLConnection =
        (URL("http://${address.host}:${address.port}/v2/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 8000; readTimeout = 120_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${address.token}")
        }
    private fun response(c: HttpURLConnection): JSONObject {
        try {
            val status = c.responseCode
            require(status in 200..299) { if (status == 403) "QR code expired. Open Receive from phone and scan again." else "Mac rejected transfer ($status): ${c.errorStream?.bufferedReader()?.readText()?.take(200)}" }
            return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: IOException) { c.disconnect(); throw IOException("Cannot reach the Mac. Both devices must be on the same Wi-Fi or local network. ${e.message}", e) }
    }
    fun peerName(): String = response(connection("hello", "GET")).getString("name").filterNot { it.isISOControl() }.trim().take(60).ifEmpty { "a Mac" }
    fun send(pack: LibraryPack, progress: (Int, Long) -> Unit = { _, _ -> }): JSONObject {
        val start = connection("manifest", "POST").apply { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        start.outputStream.use { it.write(pack.manifest.json().toString().toByteArray()) }
        val answer = response(start)
        val wanted = answer.getJSONArray("needed").let { a -> List(a.length()) { a.getString(it) }.toSet() }
        val bytes = AtomicLong(0); val count = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = pack.manifest.entries.filter { it.path in wanted }.distinctBy { it.sha256 }.map { entry -> pool.submit {
                val c = connection("file/${entry.sha256}", "PUT").apply { doOutput = true; setChunkedStreamingMode(256 * 1024) }
                val gzip = entry.path.endsWith(".xml") || entry.path.endsWith(".json")
                if (gzip) c.setRequestProperty("Content-Encoding", "gzip")
                val output = c.outputStream.let { if (gzip) GZIPOutputStream(it, 64 * 1024) else it }
                output.use { out -> pack.open(entry.path).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); progress(count.get().toInt(), bytes.addAndGet(n.toLong())) }
                } }
                response(c); progress(count.incrementAndGet().toInt(), bytes.get())
            } }
            jobs.forEach { it.get() }
        } finally { pool.shutdownNow() }
        return response(connection("complete", "POST"))
    }
}
