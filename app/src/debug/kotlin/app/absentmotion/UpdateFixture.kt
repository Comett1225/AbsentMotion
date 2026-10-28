package app.absentmotion

import app.absentmotion.ui.home.UpdateSource
import java.net.URI

internal class UpdateFixture(base: String) : UpdateSource {
    private val root = URI(base.trim().trimEnd('/'))
    override val https = false
    override val label = "Test fixture: " + root.host + ":" + root.port
    override val releases = "$root/releases"

    override fun asset(tag: String, name: String) = "$root/releases/download/$tag/$name"

    override fun allows(url: URI, first: Boolean) =
        url.isAbsolute && url.scheme == root.scheme && url.host == root.host && url.port == root.port && url.rawUserInfo == null && url.rawFragment == null
}
