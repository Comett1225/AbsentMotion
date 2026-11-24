package app.absentmotion.library

import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal class ProjectLinkImportFailure(val detail: String) : IOException(detail)

private const val MaxProjectDownload = 256L shl 20
internal const val AlightStorageHost = "firebasestorage.googleapis.com"
private const val AlightStorageRoot = "/v0/b/alight-creative.appspot.com/o/"
private const val AlightId = "[A-Za-z0-9_-]{1,128}"
private val AlightSharePath = Regex("/am/(share/u/$AlightId/p/$AlightId)/?")
private val AlightShortPath = Regex("/[A-Za-z0-9_-]{1,256}/?")
private val AlightArchivePath = Regex(Regex.escape(AlightStorageRoot) + "share%2Fu%2F$AlightId%2Fp%2F$AlightId%2Fprojectfiles[.]zip")

internal fun isAlightShare(link: URI): Boolean = when (link.host?.lowercase()) {
    "alight.link", "alight.page.link" -> true
    "alightcreative.com", "www.alightcreative.com" -> link.path?.startsWith("/am/share/") == true
    else -> false
}

internal fun projectFileLink(text: String): URI? {
    val trimmed = text.trim { it.isWhitespace() || it.category == CharCategory.FORMAT }
    if (trimmed.length > 8192 || trimmed.any { it == '\\' || it.isISOControl() }) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (uri.scheme == null && uri.host == null) {
        return runCatching { URI("https://$trimmed") }.getOrNull()?.takeIf(::alightPage)
    }
    if (uri.host == null || uri.rawUserInfo != null || uri.rawFragment != null || uri.port > 65535) return null
    return uri.takeIf { it.scheme.equals("https", true) || it.scheme.equals("http", true) && isAlightShare(it) }
}

internal fun projectFileHttp(link: URI) = linkHttp { url ->
    if (isAlightShare(link)) alightPage(url) || alightArchive(url)
    else projectFileLink(url.toString()) != null && url.scheme.equals("https", true) &&
        link.host.equals(url.host, true) && (url.port.takeUnless { it == -1 } ?: 443) == (link.port.takeUnless { it == -1 } ?: 443)
}

internal fun linkHttp(quiet: Boolean = false, timeout: Int = 10_000, allows: (URI) -> Boolean) = TrustedHttp(object : UpdateSource {
    override val releases = ""
    override fun asset(tag: String, name: String) = ""
    override fun allows(url: URI, first: Boolean) = allows(url)
}, connectMs = timeout, readMs = timeout, quiet = quiet)

internal fun standardHttps(url: URI) = url.scheme.equals("https", true) &&
    (url.port == -1 || url.port == 443) && url.rawUserInfo == null && url.rawFragment == null

internal fun alightPage(url: URI): Boolean {
    if (!standardHttps(url)) return false
    val path = url.rawPath.orEmpty()
    return when (url.host?.lowercase()) {
        "alight.link", "alight.page.link" -> AlightShortPath.matches(path) || path == "/" && !url.rawQuery.isNullOrEmpty()
        "alightcreative.com", "www.alightcreative.com" -> AlightSharePath.matches(path)
        else -> false
    }
}

private fun alightArchive(url: URI) = standardHttps(url) && url.host.equals(AlightStorageHost, true) &&
    AlightArchivePath.matches(url.rawPath.orEmpty()) && url.rawQuery == "alt=media"

internal fun alightPackageUrl(share: URI): URI? {
    if (!alightPage(share)) return null
    val objectPath = AlightSharePath.matchEntire(share.rawPath)?.groupValues?.get(1) ?: return null
    return URI("https://$AlightStorageHost$AlightStorageRoot${objectPath.replace("/", "%2F")}%2Fprojectfiles.zip?alt=media")
}

internal fun alightHttps(link: URI): URI = if (link.scheme.equals("http", true)) URI("https:" + link.rawSchemeSpecificPart) else link

internal fun externalAlightLink(text: String): URI? {
    if (text.length > 8192) return null
    fun share(value: String) = projectFileLink(value)?.let(::alightHttps)?.takeIf(::alightPage)
    return share(text) ?: text.split(Regex("\\s+")).mapNotNull(::share).distinct().singleOrNull()
}

internal fun resolveAlightPage(link: URI, http: TrustedHttp): URI {
    val page = alightHttps(link)
    if (!alightPage(page)) alightUnavailable()
    if (alightPackageUrl(page) != null) return page
    return http.get(page.toString()) { response ->
        response.url.toURI().takeIf { response.responseCode == HttpURLConnection.HTTP_OK && alightPackageUrl(it) != null }
            ?: alightUnavailable()
    }
}

private fun alightDownload(link: URI, http: TrustedHttp): URI = alightPackageUrl(resolveAlightPage(link, http)) ?: alightUnavailable()

private fun alightUnavailable(): Nothing = throw ImportFailed(txt(R.string.project_link_alight))

internal suspend fun <T> withProjectLinkFile(link: URI, cache: File, http: TrustedHttp, use: suspend (File) -> T): T {
    val alight = isAlightShare(link)
    var temporary: File? = null
    try {
        runInterruptible(Dispatchers.IO) {
            val download = if (alight) alightDownload(link, http) else link
            http.get(download.toString()) { response ->
                if (response.responseCode != HttpURLConnection.HTTP_OK) {
                    if (alight && response.responseCode in 400..499) alightUnavailable()
                    throw IOException()
                }
                if (response.contentType?.substringBefore(';')?.trim()?.lowercase() in setOf("text/html", "application/xhtml+xml")) {
                    throw ImportFailed(txt(R.string.project_link_web_page))
                }
                val expected = response.contentLengthLong
                if (expected > MaxProjectDownload) throw ImportFailed(txt(R.string.project_link_too_large))
                val target = File.createTempFile("project-link-", ".tmp", cache).also { temporary = it }
                response.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > MaxProjectDownload) throw ImportFailed(txt(R.string.project_link_too_large))
                            output.write(buffer, 0, count)
                        }
                        if (bytes == 0L || expected >= 0 && bytes != expected) throw IOException()
                    }
                }
            }
        }
        return use(requireNotNull(temporary))
    } finally {
        temporary?.delete()
    }
}
