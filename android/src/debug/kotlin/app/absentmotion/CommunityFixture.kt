package app.absentmotion

import app.absentmotion.ui.home.UpdateSource
import java.net.URI

internal class CommunityFixture(base: String) : UpdateSource {
    private val root = URI(base.trim().trimEnd('/'))
    override val https = false
    override val releases = "$root/"

    override fun asset(tag: String, name: String) = "$root/${name.trimStart('/')}"

    override fun allows(url: URI, first: Boolean) =
        url.isAbsolute && url.scheme == root.scheme && url.host == root.host && url.port == root.port && url.rawUserInfo == null && url.rawFragment == null &&
            url.rawPath.orEmpty().startsWith(root.rawPath.orEmpty()) && ".." !in url.rawPath.orEmpty()
}
