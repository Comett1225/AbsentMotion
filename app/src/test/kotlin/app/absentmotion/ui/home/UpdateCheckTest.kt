package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
