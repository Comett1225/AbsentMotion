package app.absentmotion.library

import app.absentmotion.platform.readAtMost
import app.absentmotion.ui.home.TrustedHttp
import java.net.HttpURLConnection
import java.net.URI

private const val MaxSharePage = 128 shl 10
private const val MaxThumbnail = 1 shl 20
private val MetaTags = Regex("""<meta\b(?:[^"'<>]|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
private val Attributes = Regex("""([a-z][\w:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
private val Entities = Regex("""&(#x[0-9a-f]{1,6}|#[0-9]{1,7}|amp|quot|apos|lt|gt|nbsp);""", RegexOption.IGNORE_CASE)
private val ThumbnailName = Regex("""thumb-(?:tiny|small|med|large)\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
private val ThumbnailToken = Regex("""token=[a-fA-F0-9-]{36}""")

internal data class AlightLinkPreview(val title: String, val description: String?, val thumbnail: ByteArray?)

internal fun alightPreviewHttp() = linkHttp(quiet = true, timeout = 5_000, allows = ::alightPage)

internal fun alightLinkPreview(link: URI, http: TrustedHttp): AlightLinkPreview? {
    val page = alightHttps(link)
    if (!alightPage(page)) return null
    val (resolved, fields) = http.get(page.toString()) { response ->
        val resolved = response.url.toURI()
        if (response.responseCode != HttpURLConnection.HTTP_OK || alightPackageUrl(resolved) == null) return@get null
        val html = response.inputStream.use { it.readAtMost(MaxSharePage + 1) }
        if (html.size > MaxSharePage) return@get null
        resolved to alightPreviewFields(html.decodeToString())
    } ?: return null
    val title = fields["og:title"]?.take(160)?.takeIf { it.isNotBlank() } ?: return null
    val description = fields["og:description"]?.take(400)?.takeIf { it.isNotBlank() }
    val image = fields["og:image"]?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf { alightThumbnail(resolved, it) }?.let(::loadThumbnail)
    return AlightLinkPreview(title, description, image)
}

internal fun alightPreviewFields(html: String): Map<String, String> = MetaTags.findAll(html).mapNotNull { tag ->
    val fields = Attributes.findAll(tag.value).associate { attr ->
        attr.groupValues[1].lowercase() to (2..4).firstNotNullOfOrNull { attr.groups[it]?.value }.orEmpty()
    }
    val property = (fields["property"] ?: fields["name"])?.lowercase() ?: return@mapNotNull null
    val content = fields["content"]?.takeIf { it.length <= 2048 } ?: return@mapNotNull null
    property to Entities.replace(content) { match ->
        when (val entity = match.groupValues[1].lowercase()) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> (if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull())
                ?.takeIf { it in 0x20..0x10ffff && it !in 0xd800..0xdfff }?.let { String(Character.toChars(it)) } ?: match.value
        }
    }.trim()
}.toMap()

internal fun alightThumbnail(page: URI, image: URI): Boolean {
    if (!standardHttps(image) || !image.host.equals(AlightStorageHost, true)) return false
    val packagePath = alightPackageUrl(page)?.path?.substringBeforeLast('/') ?: return false
    val path = image.path ?: return false
    if (path.substringBeforeLast('/') != packagePath || !ThumbnailName.matches(path.substringAfterLast('/'))) return false
    val query = image.rawQuery?.split('&') ?: return false
    return "alt=media" in query && query.all { it == "alt=media" || ThumbnailToken.matches(it) }
}

private fun loadThumbnail(uri: URI): ByteArray? = try {
    linkHttp(quiet = true, timeout = 4_000) { it == uri }.get(uri.toString()) { response ->
        val type = response.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (response.responseCode != HttpURLConnection.HTTP_OK || response.contentLengthLong > MaxThumbnail ||
            !type.startsWith("image/") && type != "application/octet-stream"
        ) null else response.inputStream.use { it.readAtMost(MaxThumbnail + 1) }.takeIf { it.size <= MaxThumbnail }
    }
} catch (_: java.io.IOException) {
    null
}
