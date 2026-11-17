package app.absentmotion.links

import app.absentmotion.platform.readAtMost
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateSource
import app.absentmotion.ui.home.redirectTarget
import java.io.IOException
import java.net.URI
import java.util.Locale

object AlightLinks {
    private val id = "[A-Za-z0-9_-]+"
    private val shortPath = Regex("/$id")
    private val sharePath = Regex("/am/share/u/$id/p/$id/?")

    fun parse(text: String): URI? {
        if (text.length > 4096 || text.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!publicHttps(uri)) return null
        val path = uri.rawPath.orEmpty()
        val valid = when (uri.host.lowercase(Locale.ROOT)) {
            "alight.link", "alight.page.link" -> shortPath.matches(path)
            "alightcreative.com" -> sharePath.matches(path)
            else -> false
        }
        return uri.takeIf { valid }
    }

    fun extract(text: String?): String? = text?.take(65536)?.let { value ->
        Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(value).firstNotNullOfOrNull {
            it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}').takeIf { link -> parse(link) != null }
        }
    }

    internal fun publicHttps(uri: URI): Boolean = uri.isAbsolute && !uri.isOpaque && uri.scheme.equals("https", true) &&
        uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port == 443) && uri.host != null

    internal fun redirect(from: URI, location: String?): URI? = redirectTarget(from, location, AlightPageSource)

    private val storageThumb = Regex("/v0/b/alight-creative\\.appspot\\.com/o/share%2Fu%2F$id%2Fp%2F$id%2Fthumb-(tiny|small|med)\\.jpg")
    private val storageQuery = Regex("alt=media(&token=[A-Za-z0-9-]+)?")

    internal fun thumbnail(url: URI): Boolean {
        if (!publicHttps(url)) return false
        val path = url.rawPath.orEmpty()
        return when (url.host.lowercase(Locale.ROOT)) {
            "alightcreative.com" -> !path.contains('%') && path.split('/').none { it == "." || it == ".." }
            "firebasestorage.googleapis.com" -> storageThumb.matches(path) && storageQuery.matches(url.rawQuery.orEmpty())
            else -> false
        }
    }
}

internal object AlightPageSource : UpdateSource {
    override val releases = ""
    override fun asset(tag: String, name: String) = ""
    override fun allows(url: URI, first: Boolean) = AlightLinks.parse(url.toString()) != null
}

internal data class AlightPreview(val title: String? = null, val description: String? = null, val image: String? = null)

internal fun alightMeta(html: String): AlightPreview {
    val fields = linkedMapOf<String, String>()
    val tags = Regex("""<meta\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    val clean = Regex("""<!--.*?-->|<script\b[^>]*>.*?</script\s*>|<style\b[^>]*>.*?</style\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(html, "")
    tags.findAll(clean).forEach { tag ->
        val attrs = attributes.findAll(tag.value).associate { match ->
            match.groupValues[1].lowercase(Locale.ROOT) to htmlEntities(match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty())
        }
        val property = (attrs["property"] ?: attrs["name"])?.lowercase(Locale.ROOT)
        if (property in setOf("og:title", "og:description", "og:image")) attrs["content"]?.trim()?.takeIf { it.isNotEmpty() }?.let { fields.putIfAbsent(property!!, it.take(2048)) }
    }
    return AlightPreview(fields["og:title"], fields["og:description"], fields["og:image"])
}

private fun htmlEntities(text: String): String = Regex("&(#x[0-9a-f]+|#[0-9]+|[a-z]+);", RegexOption.IGNORE_CASE).replace(text) { match ->
    val name = match.groupValues[1]
    val number = when {
        name.startsWith("#x", true) -> name.substring(2).toIntOrNull(16)
        name.startsWith('#') -> name.substring(1).toIntOrNull()
        else -> null
    }
    if (number != null && Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF && number > 0) String(Character.toChars(number))
    else when (name) {
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        "nbsp" -> " "
        "ndash" -> "–"
        "mdash" -> "—"
        "hellip" -> "…"
        "lsquo", "rsquo" -> "'"
        "ldquo", "rdquo" -> "\""
        else -> match.value
    }
}

internal class AlightClient {
    private val page = TrustedHttp(AlightPageSource, 10_000, 10_000, maxRedirects = 3, totalMs = 10_000)
    private var thumbnail: TrustedHttp? = null

    fun abort() { page.abort(); thumbnail?.abort() }

    fun preview(link: String): AlightPreview = page.get(link, mapOf("Accept" to "text/html")) { connection ->
        if (connection.responseCode != 200 || !connection.contentType.orEmpty().substringBefore(';').equals("text/html", true)) throw IOException()
        alightMeta(connection.inputStream.use { it.readCapped(512 * 1024) }.toString(Charsets.UTF_8))
    }

    fun image(preview: AlightPreview): ByteArray? {
        val target = preview.image?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val source = object : UpdateSource {
            override val releases = ""
            override fun asset(tag: String, name: String) = ""
            override fun allows(url: URI, first: Boolean) = url == target && AlightLinks.thumbnail(url)
        }
        val http = TrustedHttp(source, 10_000, 10_000, maxRedirects = 0, totalMs = 10_000)
        thumbnail = http
        return http.get(target.toString(), mapOf("Accept" to "image/*")) { connection ->
            if (connection.responseCode != 200 || !connection.contentType.orEmpty().startsWith("image/", true)) throw IOException()
            connection.inputStream.use { it.readCapped(512 * 1024) }
        }
    }
}

internal fun java.io.InputStream.readCapped(cap: Int): ByteArray {
    val bytes = readAtMost(cap + 1)
    if (bytes.size > cap) throw IOException()
    return bytes
}
