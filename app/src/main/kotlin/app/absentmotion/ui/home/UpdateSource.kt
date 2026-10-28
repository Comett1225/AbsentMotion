package app.absentmotion.ui.home

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal interface UpdateSource {
    val releases: String
    val https: Boolean get() = true
    val label: String? get() = null
    val signers: Set<String>? get() = null
    fun asset(tag: String, name: String): String
    fun allows(url: URI, first: Boolean): Boolean
}

internal object GitHubSource : UpdateSource {
    private val repository = HOME_PAGE.removePrefix("https://github.com/").lowercase(Locale.ROOT)
    private val cdn = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")
    override val releases = "https://api.github.com/repos/" + HOME_PAGE.removePrefix("https://github.com/") + "/releases?per_page=30"
    override val signers = setOf(RELEASE_SIGNER)

    override fun asset(tag: String, name: String) = "$HOME_PAGE/releases/download/$tag/$name"

    override fun allows(url: URI, first: Boolean): Boolean {
        if (!url.isAbsolute || url.isOpaque || !url.scheme.equals("https", ignoreCase = true)) return false
        if (url.rawUserInfo != null || url.rawFragment != null || (url.port != -1 && url.port != 443)) return false
        val host = url.host?.lowercase(Locale.ROOT) ?: return false
        val path = url.rawPath.orEmpty().lowercase(Locale.ROOT)
        if (path.split('/').any { it == ".." || it == "." } || path.contains('\\')) return false
        val releaseFile = host == "github.com" && path.startsWith("/$repository/releases/download/")
        val listing = host == "api.github.com" && (path == "/repos/$repository/releases" || (!first && path.matches(Regex("/repositories/[0-9]+/releases"))))
        return releaseFile || listing || (!first && host in cdn)
    }
}

internal const val RELEASE_SIGNER = "5faf34792b681e8806a143e8a71c3cc550b4e5edd388ba292a46dc09cdb7e964"

internal val STORE_INSTALLERS = setOf("com.android.vending")

internal fun updatesInApp(installer: String?, signers: Set<String>, history: Set<String>, trusted: Set<String>?): Boolean {
    if (installer in STORE_INSTALLERS) return false
    if (trusted == null) return true
    return signers.size == 1 && (signers.first() in trusted || history.any { it in trusted })
}

internal const val MAX_REDIRECTS = 5

internal fun redirectTarget(from: URI, location: String?, source: UpdateSource): URI? {
    val raw = location?.trim()?.takeIf { text -> text.isNotEmpty() && text.none { it.isWhitespace() || it.code < 0x20 || it == '\\' } } ?: return null
    val next = runCatching { from.resolve(URI(raw)) }.getOrNull() ?: return null
    if (from.scheme.equals("https", ignoreCase = true) && !next.scheme.equals("https", ignoreCase = true)) return null
    return next.takeIf { source.allows(it, first = false) }
}

internal class UpdateProblem(val failure: UpdateFailure, cause: Throwable? = null) : IOException(failure.toString(), cause)

internal class TrustedHttp(private val source: UpdateSource, private val connectMs: Int = 15_000, private val readMs: Int = 30_000) {
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var aborted = false

    fun abort() {
        aborted = true
        current?.let { runCatching { it.disconnect() } }
    }

    fun <T> get(url: String, headers: Map<String, String> = emptyMap(), read: (HttpURLConnection) -> T): T {
        var target = runCatching { URI(url) }.getOrNull() ?: throw UpdateProblem(UpdateFailure.Untrusted)
        if (!source.allows(target, first = true)) throw UpdateProblem(UpdateFailure.Untrusted)
        repeat(MAX_REDIRECTS + 1) {
            if (aborted) throw UpdateProblem(UpdateFailure.Connection)
            val connection = target.toURL().openConnection() as HttpURLConnection
            if (source.https && connection !is HttpsURLConnection) throw UpdateProblem(UpdateFailure.Untrusted)
            current = connection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connectMs
                connection.readTimeout = readMs
                connection.setRequestProperty("User-Agent", "AbsentMotion")
                connection.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                val code = connection.responseCode
                if (code in REDIRECTS) {
                    target = redirectTarget(target, connection.getHeaderField("Location"), source) ?: throw UpdateProblem(UpdateFailure.Untrusted)
                } else {
                    return read(connection)
                }
            } finally {
                connection.disconnect()
                current = null
            }
        }
        throw UpdateProblem(UpdateFailure.Untrusted)
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

internal fun parseDigest(value: String?): String? =
    value?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase(Locale.ROOT)?.takeIf(::isSha256)

internal fun parseSums(text: String, name: String): String? {
    val found = text.lineSequence().mapNotNull { line ->
        val match = Regex("^([0-9a-fA-F]{64}) [ *]?(.+)$").find(line.trimEnd('\r', ' ')) ?: return@mapNotNull null
        match.groupValues[1].lowercase(Locale.ROOT).takeIf { match.groupValues[2] == name }
    }.toSet()
    return found.singleOrNull()
}

internal fun isSha256(text: String) = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
