package app.absentmotion.ui.home

import java.net.URI

internal class FeedSource(base: String) : UpdateSource {
    private val root = URI(base.trimEnd('/'))
    override val releases = "${root}/releases.json"
    override val https = false
    override val label = "Local test feed"
    override fun asset(tag: String, name: String) = "$root/download/$tag/$name"
    override fun allows(url: URI, first: Boolean) =
        url.scheme == root.scheme && url.host == root.host && url.port == root.port && url.rawPath.orEmpty().startsWith(root.rawPath.orEmpty()) && ".." !in url.rawPath.orEmpty()
}

internal fun localFeed(property: String? = System.getProperty("am.updateFeed")): UpdateSource? =
    property?.trim()?.takeIf { it.startsWith("http://127.0.0.1:") || it.startsWith("http://localhost:") }?.let { runCatching { FeedSource(it) }.getOrNull() }
