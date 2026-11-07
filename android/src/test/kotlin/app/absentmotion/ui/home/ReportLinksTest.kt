package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ReportLinksTest {
    private val readme = """
        <p align="center">
          <a href="https://discord.gg/abcDEF123"><img alt="Discord" src="https://img.shields.io/badge/Discord-join"></a>
          <a href="https://forms.gle/BugForm123"><img alt="Report a bug" src="https://img.shields.io/badge/Report-a%20bug-2b2b33?style=for-the-badge"></a>
          <a href="https://forms.gle/FeatureForm9"><img alt="Suggest a feature" src="https://img.shields.io/badge/Suggest-a%20feature"></a>
        </p>
        Use the [feature request form](https://forms.gle/FeatureForm9) or the [bug report form](https://forms.gle/BugForm123).
    """.trimIndent()

    @Test
    fun readsDiscordAndBugForm() {
        val links = parseReportLinks(readme)
        assertEquals("https://discord.gg/abcDEF123", links.discord)
        assertEquals("https://forms.gle/BugForm123", links.form)
        assertEquals(ReportLinks.BuiltIn.issues, links.issues)
    }

    @Test
    fun readsFeatureFormAndNamesEveryLink() {
        val links = parseReportLinks(readme)
        assertEquals("https://forms.gle/FeatureForm9", links.feature)
        assertEquals("https://forms.gle/BugForm123", links.named("bug-form"))
        assertEquals("https://forms.gle/FeatureForm9", links.named("feature-form"))
        assertEquals("https://discord.gg/abcDEF123", links.named("discord"))
        assertEquals(links.issues, links.named("github-issues"))
        assertEquals("https://example.com/a", links.named("https://example.com/a"))
        assertEquals(null, links.named("javascript:alert(1)"))
        assertEquals(ReportLinks.BuiltIn.feature, parseReportLinks("[Report a bug](https://forms.gle/BugForm123)").feature)
    }

    @Test
    fun acceptsLongGoogleFormLinks() {
        val long = "https://docs.google.com/forms/d/e/1FAIpQLSf_Long-Id123/viewform"
        val links = parseReportLinks("<a href=\"$long?usp=sf_link\"><img alt=\"Report a bug\"></a>\n[Suggest a feature](https://docs.google.com/forms/d/Abc_1-2/viewform)")
        assertEquals(long, links.form)
        assertEquals("https://docs.google.com/forms/d/Abc_1-2/viewform", links.feature)
        assertTrue(isFormLink(long))
        assertFalse(isFormLink("https://docs.google.com/forms/d/e/../viewform"))
        assertFalse(isFormLink("https://docs.google.com.evil.example/forms/d/e/abc/viewform"))
        assertFalse(isFormLink("https://docs.google.com/document/d/abc/edit"))
    }

    @Test
    fun acceptsInviteStyleDiscordLink() {
        assertEquals("https://discord.com/invite/xyz-1", parseReportLinks("join https://discord.com/invite/xyz-1 now").discord)
    }

    @Test
    fun fallsBackWhenNothingMatches() {
        assertEquals(ReportLinks.BuiltIn, parseReportLinks("no links here, https://example.com/discord"))
        val cached = ReportLinks.BuiltIn.copy(discord = "https://discord.gg/cached")
        assertEquals(cached, parseReportLinks("", cached))
    }

    @Test
    fun keepsFallbackFormWhenOnlyFeatureFormIsListed() {
        val links = parseReportLinks("[Suggest a feature](https://forms.gle/FeatureForm9)")
        assertEquals(ReportLinks.BuiltIn.form, links.form)
    }

    @Test
    fun linkShapesAreChecked() {
        assertTrue(isDiscordLink("https://discord.gg/xXEHj8Sx8X"))
        assertFalse(isDiscordLink("http://discord.gg/xXEHj8Sx8X"))
        assertFalse(isDiscordLink("https://evil.example/https://discord.gg/a"))
        assertTrue(isFormLink("https://forms.gle/6bY8KBPMKc2iUypV6"))
        assertFalse(isFormLink("https://forms.gle/a/../b"))
    }

    @Test
    fun readmeSourceOnlyAllowsTheReadmeEndpoint() {
        assertTrue(ReadmeSource.allows(URI(ReadmeSource.releases), true))
        val repo = HOME_PAGE.removePrefix("https://github.com/")
        assertFalse(ReadmeSource.allows(URI("https://api.github.com/repos/$repo/releases"), true))
        assertFalse(ReadmeSource.allows(URI("http://api.github.com/repos/$repo/readme"), true))
        assertFalse(ReadmeSource.allows(URI("https://example.com/repos/$repo/readme"), true))
        assertTrue(ReadmeSource.allows(URI("https://api.github.com/repositories/123/readme"), false))
        assertFalse(ReadmeSource.allows(URI("https://api.github.com/repositories/123/readme"), true))
    }

    @Test
    fun scrubRemovesHomeFolderAndAddresses() {
        val text = "at /srv/people/someone/Movies/My Film.mp4 and /home/other/x and C:\\Users\\Name\\Videos mail me@example.com in /data/user/0/app.absentmotion/files/p.xml"
        val out = scrubReport(text, "/srv/people/someone")
        assertFalse(out.contains("someone"))
        assertFalse(out.contains("other"))
        assertFalse(out.contains("Name"))
        assertFalse(out.contains("me@example.com"))
        assertTrue(out.contains("~/Movies"))
        assertTrue(out.contains("[email]"))
        assertFalse(out.contains("p.xml") && out.contains("files/p"))
    }

    @Test
    fun scrubKeepsPlainText() {
        assertEquals("Export failed at frame 12", scrubReport("Export failed at frame 12", "/"))
    }
}
