package app.absentmotion.ui.home

import app.absentmotion.library.sha256String
import app.absentmotion.platform.readAtMost
import java.io.File
import java.net.HttpURLConnection
import java.util.zip.GZIPInputStream

internal class HttpCache(private val dir: File) {
    fun get(http: TrustedHttp, url: String, maxBytes: Int, headers: Map<String, String> = emptyMap()): ByteArray? {
        val key = sha256String(url).take(32)
        val body = File(dir, "$key.body")
        val tag = File(dir, "$key.etag")
        val etag = if (body.isFile && tag.isFile) runCatching { tag.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } else null
        val ask = headers + ("Accept-Encoding" to "gzip") + (etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap())
        return http.get(url, ask) { connection ->
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> runCatching { body.readBytes() }.getOrNull()
                HttpURLConnection.HTTP_OK -> {
                    val raw = connection.inputStream
                    val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
                    val bytes = stream.use { it.readAtMost(maxBytes + 1) }
                    if (bytes.size > maxBytes) return@get null
                    connection.getHeaderField("ETag")?.let { value ->
                        runCatching { dir.mkdirs(); body.writeBytes(bytes); tag.writeText(value) }
                    } ?: runCatching { tag.delete() }
                    bytes
                }
                else -> null
            }
        }
    }
}
