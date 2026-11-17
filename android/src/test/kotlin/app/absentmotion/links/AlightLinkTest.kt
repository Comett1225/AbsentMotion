package app.absentmotion.links

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

class AlightLinkTest {
    @Test fun recognizesOnlyPublicShareRoutes() {
        for (link in listOf("https://alight.link/abc_12-XY", "https://alight.page.link/ABC123", "https://alightcreative.com/am/share/u/user-12/p/package_34", "https://alightcreative.com/am/share/u/u/p/p/", "https://ALIGHT.LINK/id?source=share", "https://alight.link:443/id")) assertNotNull(link, AlightLinks.parse(link))
    }

    @Test fun rejectsUnsafeHostsAndSchemes() {
        for (link in listOf("http://alight.link/id", "https://alight.link.evil.com/id", "https://evilalight.link/id", "https://alight.page.link.evil.com/id", "https://alightcreative.com.evil.com/am/share/u/u/p/p", "https://alight.link@evil.com/id", "https://evil.com@alight.link/id", "https://alight.link:8443/id", "https://alight.link./id", "https://alight.link/id#fragment", "https://alight.link\\@evil.com/id", "https://alight.link/id\n", " https://alight.link/id", "//alight.link/id", "https:alight.link/id", "file:///alight.link/id")) assertNull(link, AlightLinks.parse(link))
    }

    @Test fun rejectsExtraAndEncodedPaths() {
        for (link in listOf("https://alight.link/", "https://alight.link/id/extra", "https://alight.link/id/", "https://alight.page.link/id/extra", "https://alightcreative.com/am/share/anything", "https://alightcreative.com/am/share/u/u/p/p/extra", "https://alightcreative.com/am/share/u/u/p/", "https://alightcreative.com/private/project", "https://alight.link/%2fsecret", "https://alight.link/../id", "https://alight.link/%69d", "https://alight.link/a.b")) assertNull(link, AlightLinks.parse(link))
    }

    @Test fun extractsSharedTextWithoutAcceptingLookalikes() {
        assertEquals("https://alight.link/AbC", AlightLinks.extract("Try this project: (https://alight.link/AbC). 🎬"))
        assertEquals("https://alight.page.link/real", AlightLinks.extract("https://alight.link.evil.com/fake\nhttps://alight.page.link/real"))
        assertEquals("https://alightcreative.com/am/share/u/u/p/p", AlightLinks.extract("<https://alightcreative.com/am/share/u/u/p/p>"))
        for (text in listOf(null, "", "ordinary text", "http://alight.link/id", "https://alight.link/id/extra", "https://alight.link.evil.com/id")) assertNull(text, AlightLinks.extract(text))
    }

    @Test fun validatesEveryRedirect() {
        val short = URI("https://alight.link/id")
        assertEquals(URI("https://alight.page.link/id"), AlightLinks.redirect(short, "https://alight.page.link/id"))
        assertEquals(URI("https://alightcreative.com/am/share/u/u/p/p"), AlightLinks.redirect(short, "https://alightcreative.com/am/share/u/u/p/p"))
        assertEquals(URI("https://alight.link/next"), AlightLinks.redirect(short, "/next"))
        for (location in listOf(null, "", "http://alight.page.link/id", "https://alight.link.evil.com/id", "https://storage.googleapis.com/private", "https://alightcreative.com/private/project", "//evil.com/id", "https://alight.link/id/extra", "https://alight.link/id\nX")) assertNull(location, AlightLinks.redirect(short, location))
    }

    @Test fun thumbnailsOnlyFromTheSharePictures() {
        for (link in listOf("https://alightcreative.com/thumb.png", "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fuser1%2Fp%2Fpkg-2%2Fthumb-small.jpg?alt=media&token=b0e9d3f3-c171-4411-a59f-392e01396851", "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fu%2Fp%2Fp%2Fthumb-med.jpg?alt=media")) assertTrue(link, AlightLinks.thumbnail(URI(link)))
        for (link in listOf("http://alightcreative.com/thumb.png", "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fu%2Fp%2Fp%2Fproject.zip?alt=media", "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/private%2Fu%2Fp%2Fthumb-small.jpg?alt=media", "https://firebasestorage.googleapis.com/v0/b/other.appspot.com/o/share%2Fu%2Fu%2Fp%2Fp%2Fthumb-small.jpg?alt=media", "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fu%2Fp%2Fp%2Fthumb-small.jpg", "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2F..%2Fp%2Fp%2Fthumb-small.jpg?alt=media", "https://evil.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fu%2Fp%2Fp%2Fthumb-small.jpg?alt=media", "https://alightcreative.com/a/../thumb.png")) assertFalse(link, AlightLinks.thumbnail(URI(link)))
    }

    @Test fun parsesMetaInAnyOrderWithEntitiesAndEmoji() {
        val meta = alightMeta("""<META content='Scene &amp; lights &#x1F3AC; 🎨 &quot;edit&quot;' property="og:title"><meta property='og:description' content="1 project, total 73.7 kb. &lt;XML&gt; &apos;yes&apos; &#128512;"><meta content=https://alightcreative.com/thumb.png?x=1&amp;y=2 property=og:image>""")
        assertEquals("Scene & lights 🎬 🎨 \"edit\"", meta.title)
        assertEquals("1 project, total 73.7 kb. <XML> 'yes' 😀", meta.description)
        assertEquals("https://alightcreative.com/thumb.png?x=1&y=2", meta.image)
    }

    @Test fun missingFieldsAndMarkupAreSafe() {
        assertEquals(AlightPreview(), alightMeta("<html><title>not metadata</title></html>"))
        assertEquals(AlightPreview(title = "One > Two"), alightMeta("""<!-- <meta property="og:image" content="bad"> --><script> '<meta property="og:title" content="bad">' </script><meta content="One > Two" name="og:title"><meta property="og:title" content="second"><meta property="og:description"><meta property="og:image" content="">"""))
        assertEquals("&amp;", alightMeta("""<meta property="og:title" content="&amp;amp;">""").title)
    }

    @Test fun capsResponsesWithoutTruncatingHtml() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readCapped(2))
        try { ByteArrayInputStream(byteArrayOf(1, 2, 3)).readCapped(2); fail("oversized response") } catch (_: IOException) { }
    }
}
