package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun versionsCompareAsNumbersNotText() {
        assertEquals(true, isNewer("3.0.10", "3.0.0"))
        assertEquals(false, isNewer("3.0.0", "3.0.10"))
        assertEquals(true, isNewer("3.0.10", "3.0.9"))
        assertEquals(true, isNewer("3.1.0", "3.0.10"))
        assertEquals(true, isNewer("10.0.0", "9.9.9"))
        assertEquals(false, isNewer("3.0.0", "3.0.0"))
        assertEquals(false, isNewer("3.0", "3.0.0"))
        assertEquals(true, isNewer("3.0.1", "3"))
    }

    @Test fun aLeadingVIsIgnored() {
        assertEquals(false, isNewer("v3.0.0", "3.0.0"))
        assertEquals(true, isNewer("v3.1.0", "3.0.0"))
        assertEquals(false, isNewer("V3.0.0", "v3.0.0"))
        assertEquals(false, isNewer(" v3.0.0 ", "3.0.0"))
    }

    @Test fun preReleasesComeBeforeTheirRelease() {
        assertEquals(false, isNewer("v3.1.0-beta.1", "3.1.0"))
        assertEquals(true, isNewer("v3.1.0", "3.1.0-rc.2"))
        assertEquals(true, isNewer("v3.1.0-beta.1", "3.0.0"))
        assertEquals(true, isNewer("3.1.0-beta.11", "3.1.0-beta.2"))
        assertEquals(true, isNewer("3.1.0-rc.1", "3.1.0-beta.9"))
        assertEquals(true, isNewer("3.1.0-beta.1", "3.1.0-beta"))
        assertEquals(true, isNewer("3.1.0-beta", "3.1.0-7"))
        assertEquals(false, isNewer("3.1.0+build.7", "3.1.0"))
    }

    @Test fun unreadableVersionsAreNeverOffered() {
        assertNull(isNewer("latest", "3.0.0"))
        assertNull(isNewer("v3..1", "3.0.0"))
        assertNull(isNewer("-1.0.0", "3.0.0"))
        assertNull(isNewer("3.1.0", ""))
    }

    @Test fun theReleaseJsonGivesTheVersionAndItsPage() {
        val page = "$HOME_PAGE/releases/tag/v3.1.0"
        val json = """{"url":"x","tag_name":"v3.1.0","html_url":"$page","name":"AbsentMotion 3.1.0","prerelease":false,"assets":[{"name":"a.apk"}]}"""
        assertEquals(Release("3.1.0", page), parseRelease(json))
        assertEquals(Release("3.1.0-rc.1", "$HOME_PAGE/releases/tag/v3.1.0-rc.1"), parseRelease("""{"tag_name":" v3.1.0-rc.1","html_url":"$HOME_PAGE/releases/tag/v3.1.0-rc.1"}"""))
    }

    @Test fun onlyThisRepositoryIsEverOpened() {
        val latest = "$HOME_PAGE/releases/latest"
        assertEquals(Release("3.1.0", latest), parseRelease("""{"tag_name":"v3.1.0","html_url":"https://example.com/x"}"""))
        assertEquals(Release("3.1.0", latest), parseRelease("""{"tag_name":"v3.1.0","html_url":"intent://scan#Intent;end"}"""))
        assertEquals(Release("3.1.0", latest), parseRelease("""{"tag_name":"v3.1.0","html_url":"${HOME_PAGE}Fake/releases"}"""))
        assertEquals(Release("3.1.0", latest), parseRelease("""{"tag_name":"v3.1.0"}"""))
    }

    @Test fun downloadOpensTheApkOfTheInstalledVersion() {
        val tag = "v3.4.0"
        fun asset(name: String) = """{"name":"$name","size":1000,"browser_download_url":"$HOME_PAGE/releases/download/$tag/$name"}"""
        val json = """{"tag_name":"$tag","html_url":"$HOME_PAGE/releases/tag/$tag","assets":[${asset("AbsentMotion-3.4.0-foss.apk")},""" +
            """${asset("AbsentMotion-3.4.0-play.apk")},${asset("AbsentMotion-3.4.0-compatible.apk")},${asset("SHA256SUMS.txt")}]}"""
        assertEquals("$HOME_PAGE/releases/download/$tag/AbsentMotion-3.4.0-foss.apk", parseRelease(json, "foss")?.download)
        assertEquals("$HOME_PAGE/releases/download/$tag/AbsentMotion-3.4.0-play.apk", parseRelease(json, "play")?.download)
        assertEquals("$HOME_PAGE/releases/download/$tag/AbsentMotion-3.4.0-compatible.apk", parseReleases("[$json]", "compat")?.download)
        assertEquals("$HOME_PAGE/releases/tag/$tag", parseRelease(json, "foss")?.link)
    }

    @Test fun aMissingOrForeignApkFallsBackToTheReleasePage() {
        val tag = "v3.4.0"
        fun one(name: String, url: String) =
            parseRelease("""{"tag_name":"$tag","html_url":"$HOME_PAGE/releases/tag/$tag","assets":[{"name":"$name","size":1000,"browser_download_url":"$url"}]}""", "foss")?.download
        assertNull(one("AbsentMotion-3.4.0-play.apk", "$HOME_PAGE/releases/download/$tag/AbsentMotion-3.4.0-play.apk"))
        assertNull(one("AbsentMotion-3.4.0-foss.apk", "https://example.com/AbsentMotion-3.4.0-foss.apk"))
        assertNull(one("AbsentMotion-3.4.0-foss.apk", "$HOME_PAGE/releases/download/v3.3.0/AbsentMotion-3.4.0-foss.apk"))
        assertNull(one("AbsentMotion-3.3.0-foss.apk", "$HOME_PAGE/releases/download/$tag/AbsentMotion-3.3.0-foss.apk"))
        assertNull(one("AbsentMotion-3.4.0-foss.apk", "intent://scan#Intent;end"))
        assertNull(parseRelease("""{"tag_name":"$tag","html_url":"$HOME_PAGE/releases/tag/$tag"}""", "foss")?.download)
    }

    @Test fun errorsAndOddAnswersGiveNoRelease() {
        assertNull(parseRelease("""{"message":"Not Found","documentation_url":"https://docs.github.com"}"""))
        assertNull(parseRelease("""{"tag_name":"nightly"}"""))
        assertNull(parseRelease("""[{"tag_name":"v3.1.0"}]"""))
        assertNull(parseRelease("<html>rate limited</html>"))
        assertNull(parseRelease("""{"tag_name":"v3.1"""))
        assertNull(parseRelease(""))
    }

    @Test fun theNewestVersionWinsWhateverElseTheRepositoryPublishes() {
        fun release(tag: String, extra: String = "") = """{"tag_name":"$tag","html_url":"$HOME_PAGE/releases/tag/$tag"$extra}"""
        val list = "[" + listOf(
            release("ai-models-1"), release("v3.4.0", ""","draft":true"""), release("v3.5.0-beta.1", ""","prerelease":true"""),
            release("v3.3.0"), release("v3.3.10"), release("v3.3.9"), release("models-v2"), release("v2.5.2"),
        ).joinToString(",") + "]"
        assertEquals(Release("3.3.10", "$HOME_PAGE/releases/tag/v3.3.10"), parseReleases(list))
        assertEquals(Release("3.3.0", "$HOME_PAGE/releases/tag/v3.3.0"), parseReleases("[" + listOf(release("ai-models-1"), release("v3.3.0")).joinToString(",") + "]"))
    }

    @Test fun aListWithNothingToOfferGivesNoRelease() {
        assertNull(parseReleases("[]"))
        assertNull(parseReleases("""[{"tag_name":"ai-models-1"},{"tag_name":"nightly"}]"""))
        assertNull(parseReleases("""[{"tag_name":"v3.4.0","draft":true},{"tag_name":"v3.5.0","prerelease":true}]"""))
        assertNull(parseReleases("""{"tag_name":"v3.1.0"}"""))
        assertNull(parseReleases("""{"message":"API rate limit exceeded"}"""))
        assertNull(parseReleases("[1, null, \"x\"]"))
        assertNull(parseReleases("<html>rate limited</html>"))
        assertNull(parseReleases("[{\"tag_name\":\"v3.1"))
        assertNull(parseReleases(""))
    }

    @Test fun theOutdatedHintOnlyAppearsWhenAllowedAndReallyOlder() {
        val page = "$HOME_PAGE/releases/tag/v3.2.0"
        assertEquals(Outdated("3.1.0", "3.2.0", page), outdatedHint(allowed = true, installed = "3.1.0", newest = "3.2.0", link = page))
        assertEquals(Outdated("3.1.0", "3.1.1-rc.1", page), outdatedHint(true, "3.1.0", "3.1.1-rc.1", page))
        assertNull(outdatedHint(allowed = false, installed = "3.1.0", newest = "3.2.0", link = page))
        assertNull(outdatedHint(true, "3.2.0", "3.2.0", page))
        assertNull(outdatedHint(true, "3.3.0", "3.2.0", page))
        assertNull(outdatedHint(true, "3.2.0", "3.2.0-beta.4", page))
        assertNull(outdatedHint(true, "3.1.0", null, null))
        assertNull(outdatedHint(true, "3.1.0", "3.2.0", null))
        assertNull(outdatedHint(true, "3.1.0", "nightly", page))
        assertNull(outdatedHint(true, "", "3.2.0", page))
        assertNull(outdatedHint(true, "3.1.0", "3.2.0", "https://example.com/AbsentMotion.apk"))
    }

    @Test fun theAnswerSaysWhatToShow() {
        val newer = Release("3.1.0", HOME_PAGE)
        assertEquals(UpdateAnswer.Available(newer), answerFor(newer, "3.0.0"))
        assertEquals(UpdateAnswer.Latest, answerFor(Release("3.0.0", HOME_PAGE), "3.0.0"))
        assertEquals(UpdateAnswer.Latest, answerFor(Release("2.5.2", HOME_PAGE), "3.0.0"))
        assertEquals(UpdateAnswer.Failed, answerFor(null, "3.0.0"))
        assertEquals(UpdateAnswer.Failed, answerFor(newer, ""))
    }

    @Test fun theApkCarriesItsSizeChecksumAndChecksumList() {
        val tag = "v4.1.4"
        val sha = "ab".repeat(32)
        fun asset(name: String, extra: String = "") = """{"name":"$name","size":104857600$extra,"browser_download_url":"$HOME_PAGE/releases/download/$tag/$name"}"""
        val json = """{"tag_name":"$tag","html_url":"$HOME_PAGE/releases/tag/$tag","assets":[${asset("AbsentMotion-4.1.4-foss.apk", ""","digest":"sha256:$sha"""")},${asset("SHA256SUMS.txt")}]}"""
        val apk = parseRelease(json, "foss")?.apk
        assertEquals(ApkAsset("AbsentMotion-4.1.4-foss.apk", "$HOME_PAGE/releases/download/$tag/AbsentMotion-4.1.4-foss.apk", 104857600, sha, "$HOME_PAGE/releases/download/$tag/SHA256SUMS.txt"), apk)
        val older = """{"tag_name":"$tag","assets":[${asset("AbsentMotion-4.1.4-foss.apk", ""","digest":null""")},{"name":"SHA256SUMS.txt","browser_download_url":"https://example.com/SHA256SUMS.txt"}]}"""
        assertEquals(null, parseRelease(older, "foss")?.apk?.sha256)
        assertEquals(null, parseRelease(older, "foss")?.apk?.sums)
    }

    @Test fun unsafeSizesVersionsAndFlavoursGiveNoApk() {
        fun apk(tag: String, version: String, size: String, flavour: String = "foss"): ApkAsset? {
            val name = "AbsentMotion-$version-${if (flavour == "compat") "compatible" else flavour}.apk"
            return parseRelease("""{"tag_name":"$tag","assets":[{"name":"$name","size":$size,"browser_download_url":"$HOME_PAGE/releases/download/$tag/$name"}]}""", flavour)?.apk
        }
        assertEquals(1000L, apk("v4.1.4", "4.1.4", "1000")?.size)
        assertEquals(1000L, apk("v4.1.4-test", "4.1.4-test", "1000")?.size)
        assertNull(apk("v4.1.4", "4.1.4", "0"))
        assertNull(apk("v4.1.4", "4.1.4", "-1"))
        assertNull(apk("v4.1.4", "4.1.4", (MAX_APK_BYTES + 1).toString()))
        assertNull(apk("v4.1.4", "4.1.4", "\"big\""))
        assertNull(apk("v4.1.4-../../x", "4.1.4-../../x", "1000"))
        assertNull(apk("v4.1.4-a/b", "4.1.4-a/b", "1000"))
        assertNull(apk("v4.1.4", "4.1.4", "1000", "debug"))
        assertEquals("AbsentMotion-4.1.4-compatible.apk", apk("v4.1.4", "4.1.4", "1000", "compat")?.name)
    }

    @Test fun aMeteredConnectionIsMentionedOnlyForDownloadsInTheApp() {
        val apk = ApkAsset("AbsentMotion-9.9.9-foss.apk", "$HOME_PAGE/releases/download/v9.9.9/AbsentMotion-9.9.9-foss.apk", 10, "a".repeat(64), null)
        val release = Release("9.9.9", "$HOME_PAGE/releases/tag/v9.9.9", apk)
        assertTrue(warnMetered(UpdateAnswer.Available(release), true))
        assertFalse(warnMetered(UpdateAnswer.Available(release), false))
        assertFalse(warnMetered(UpdateAnswer.Available(release), null))
        assertFalse(warnMetered(UpdateAnswer.Available(release.copy(apk = null)), true))
        assertFalse(warnMetered(offerFor(UpdateAnswer.Available(release), inApp = false) as UpdateAnswer.Available, true))
    }

    @Test fun aCopyThatCannotUpdateItselfIsOnlyToldWhereUpdatesComeFrom() {
        val apk = ApkAsset("AbsentMotion-9.9.9-foss.apk", "$HOME_PAGE/releases/download/v9.9.9/AbsentMotion-9.9.9-foss.apk", 10, "a".repeat(64), null)
        val release = Release("9.9.9", "$HOME_PAGE/releases/tag/v9.9.9", apk)
        val available = UpdateAnswer.Available(release)
        assertEquals(available, offerFor(available, inApp = true))
        assertEquals(UpdateAnswer.Available(release.copy(apk = null), external = true), offerFor(available, inApp = false))
        val pageOnly = UpdateAnswer.Available(release.copy(apk = null))
        assertEquals(pageOnly, offerFor(pageOnly, inApp = false))
        assertEquals(UpdateAnswer.Latest, offerFor(UpdateAnswer.Latest, inApp = false))
        assertEquals(UpdateAnswer.Failed, offerFor(UpdateAnswer.Failed, inApp = false))
    }
}
