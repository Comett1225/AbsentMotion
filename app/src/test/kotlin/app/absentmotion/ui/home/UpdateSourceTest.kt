package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class UpdateSourceTest {
    private val asset = "$HOME_PAGE/releases/download/v4.1.3/AbsentMotion-4.1.3-foss.apk"

    @Test fun onlyTheReleaseListAndReleaseFilesOfThisRepositoryMayStartARequest() {
        assertTrue(GitHubSource.allows(URI(GitHubSource.releases), first = true))
        assertTrue(GitHubSource.allows(URI(asset), first = true))
        assertFalse(GitHubSource.allows(URI(asset.replace("https://", "http://")), first = true))
        assertFalse(GitHubSource.allows(URI("https://github.com/someone/AbsentMotion/releases/download/v4.1.3/a.apk"), first = true))
        assertFalse(GitHubSource.allows(URI(asset.replace("https://github.com/", "https://github.com:8443/")), first = true))
        assertFalse(GitHubSource.allows(URI(asset.replace("https://github.com/", "https://user@github.com/")), first = true))
        assertFalse(GitHubSource.allows(URI("$HOME_PAGE/releases/download/../../x/a.apk"), first = true))
        assertFalse(GitHubSource.allows(URI("https://objects.githubusercontent.com/github-production-release-asset/1/a"), first = true))
        assertFalse(GitHubSource.allows(URI("https://api.github.com/repositories/1/releases"), first = true))
        assertFalse(GitHubSource.allows(URI("https://example.com/AbsentMotion.apk"), first = true))
    }

    @Test fun redirectsMayOnlyGoToGitHubReleaseStorage() {
        val from = URI(asset)
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x%2By"
        assertEquals(URI(cdn), redirectTarget(from, cdn, GitHubSource))
        assertTrue(redirectTarget(from, "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/abc", GitHubSource) != null)
        assertEquals(URI("$HOME_PAGE/releases/download/v4.1.3/other.apk"), redirectTarget(from, "other.apk", GitHubSource))
        assertEquals(URI("https://api.github.com/repositories/42/releases"), redirectTarget(URI(GitHubSource.releases), "/repositories/42/releases", GitHubSource))
    }

    @Test fun foreignDowngradedOrMalformedRedirectsAreRefused() {
        val from = URI(asset)
        for (location in listOf(
            null, "", "   ", "https://example.com/a.apk", "http://objects.githubusercontent.com/a", "https://objects.githubusercontent.com.example.com/a",
            "https://user:pw@objects.githubusercontent.com/a", "https://objects.githubusercontent.com:444/a", "//example.com/a.apk",
            "https://objects.githubusercontent.com/a b", "https://objects.githubusercontent.com/a\r\nX: y", "javascript:alert(1)",
            "intent://scan#Intent;end", "file:///sdcard/a.apk", "/someone/else/releases/download/v1/a.apk", "https://objects.githubusercontent.com\\@example.com/",
            "$HOME_PAGE/releases/download/../../../x", "https://objects.githubusercontent.com/a#frag",
        )) assertNull(location, redirectTarget(from, location, GitHubSource))
    }

    @Test fun releaseDigestsMustBeSha256() {
        val hex = "a".repeat(64)
        assertEquals(hex, parseDigest("sha256:$hex"))
        assertEquals(hex, parseDigest("SHA256:${hex.uppercase()}"))
        assertNull(parseDigest("sha512:$hex"))
        assertNull(parseDigest("sha256:${hex.dropLast(1)}"))
        assertNull(parseDigest("sha256:${"g".repeat(64)}"))
        assertNull(parseDigest(null))
        assertNull(parseDigest(""))
    }

    @Test fun checksumListsGiveTheLineOfTheExactFile() {
        val a = "1".repeat(64)
        val b = "2".repeat(64)
        val sums = "$a  AbsentMotion-4.1.3-foss.apk\r\n$b *AbsentMotion-4.1.3-play.apk\n${"3".repeat(64)}  AbsentMotion-4.1.3-foss.apk.idsig\n"
        assertEquals(a, parseSums(sums, "AbsentMotion-4.1.3-foss.apk"))
        assertEquals(b, parseSums(sums, "AbsentMotion-4.1.3-play.apk"))
        assertNull(parseSums(sums, "AbsentMotion-4.1.3-compatible.apk"))
        assertNull(parseSums("$a  x.apk\n$b  x.apk\n", "x.apk"))
        assertEquals(a, parseSums("$a  x.apk\n$a  x.apk\n", "x.apk"))
        assertNull(parseSums("<html>not found</html>", "x.apk"))
    }
}
