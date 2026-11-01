package android.net

import java.io.File
import java.net.URI
import java.net.URLDecoder

class Uri private constructor(private val text: String) : Comparable<Uri> {
    private val parsed: URI? = runCatching { URI(text) }.getOrNull()
    val scheme: String? get() = parsed?.scheme ?: if (text.startsWith("/")) null else text.substringBefore(':', "").ifEmpty { null }
    val path: String? get() = parsed?.path ?: if (text.startsWith("/")) text else text.substringAfter(':', "").substringBefore('?').ifEmpty { null }
    val authority: String? get() = parsed?.authority
    val host: String? get() = parsed?.host
    val query: String? get() = parsed?.query
    val fragment: String? get() = parsed?.fragment
    val lastPathSegment: String? get() = path?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null }
    val pathSegments: List<String> get() = path?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
    val isAbsolute: Boolean get() = scheme != null
    fun getQueryParameter(key: String): String? = query?.split('&')?.firstNotNullOfOrNull { part ->
        val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (URLDecoder.decode(k, "UTF-8") == key) URLDecoder.decode(v, "UTF-8") else null
    }
    fun toFile(): File? = when {
        text.startsWith("/") -> File(text)
        scheme == "file" -> runCatching { File(parsed!!) }.getOrNull() ?: path?.let(::File)
        else -> null
    }
    fun buildUpon() = Builder(text)
    override fun toString() = text
    override fun equals(other: Any?) = other is Uri && other.text == text
    override fun hashCode() = text.hashCode()
    override fun compareTo(other: Uri) = text.compareTo(other.text)

    class Builder(private var base: String = "") {
        private val params = mutableListOf<Pair<String, String>>()
        fun scheme(s: String) = apply { base = "$s:" + base.substringAfter(':', base) }
        fun appendQueryParameter(k: String, v: String) = apply { params += k to v }
        fun build(): Uri = parse(if (params.isEmpty()) base else base + (if ('?' in base) "&" else "?") + params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" })
    }

    companion object {
        val EMPTY = Uri("")
        fun parse(text: String): Uri = Uri(text)
        fun fromFile(file: File): Uri = Uri(file.absoluteFile.toURI().toString().replaceFirst("file:/", "file:///").replace("file:////", "file:///"))
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
        fun withAppendedPath(base: Uri, segment: String) = Uri(base.toString().trimEnd('/') + "/" + encode(segment))
        fun fromParts(scheme: String, ssp: String, fragment: String?) = Uri("$scheme:$ssp" + (fragment?.let { "#$it" } ?: ""))
    }
}
