package app.absentmotion.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MediaAccessTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var files: File
    private lateinit var access: MediaAccess

    @Before fun setUp() {
        files = tmp.newFolder("data", "files")
        access = MediaAccess.forPhone(files)
    }

    private fun file(path: String) = File(files, path).apply { parentFile.mkdirs(); writeText("x") }

    @Test fun providerUrisAreAllowedWhenTheyNameAProvider() {
        assertTrue(access.allows("content://media/external/images/media/12"))
        assertTrue(access.allows("content://app.absentmotion.files/files/ai/a.png"))
        assertFalse(access.allows("content:///x"))
        assertFalse(access.allows("content:x"))
        assertFalse(access.allows("content:"))
    }

    @Test fun filesInTheMediaFoldersAreAllowedInEveryFormOfFileUri() {
        val picture = file("media/p1/a.jpg")
        assertTrue(access.allows(picture.toURI().toString()))
        assertTrue(access.allows("file://" + picture.path))
        assertTrue(access.allows(file("templates/t1/media/1_a.png").toURI().toString()))
        assertTrue(access.allows(File(files, "media/not-yet-copied/b.mp4").toURI().toString()))
    }

    @Test fun theAppsOtherFilesAreNot() {
        for (private in listOf("projects/p1.xml", "projects/p1.png", "preferences/settings.json", "fonts/imported/a.ttf", "templates/t1/scene.xml",
            "templates/t1/thumb.png", "models/ggml.bin", "presets/x.xml")) {
            assertFalse(private, access.allows(file(private).toURI().toString()))
        }
        assertFalse(access.allows(File(files, "media").toURI().toString()))
        assertFalse(access.allows(File(files, "templates/t1/media").toURI().toString()))
        val outside = File(files.parentFile, "shared_prefs/settings.xml").apply { parentFile.mkdirs(); writeText("x") }
        assertFalse(access.allows(outside.toURI().toString()))
        assertFalse(access.allows("file:///etc/hosts"))
        assertFalse(access.allows("file:///data/user/0/app.absentmotion/files/projects/p1.xml"))
    }

    @Test fun dotDotAndSymbolicLinksCannotLeaveTheMediaFolders() {
        val secret = file("preferences/settings.json")
        assertFalse(access.allows("file://" + files.path + "/media/../preferences/settings.json"))
        assertFalse(access.allows("file://" + files.path + "/media/p1/../../projects/p1.xml"))
        assertFalse(access.allows("file://" + files.path + "/media/%2e%2e/preferences/settings.json"))
        val link = File(files, "media/p1/link.jpg").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertFalse(access.allows(link.toURI().toString()))
        val folderLink = File(files, "media/p2").toPath()
        Files.createSymbolicLink(folderLink, File(files, "fonts").apply { mkdirs() }.toPath())
        assertFalse(access.allows(File(files, "media/p2/a.ttf").toURI().toString()))
    }

    @Test fun otherSchemesAndOddUrisAreNot() {
        val picture = file("media/p1/a.jpg")
        for (uri in listOf("", "http://example.com/a.jpg", "https://example.com/a.jpg", "amproj:a.jpg", "x://a", "/etc/passwd", picture.path,
            "file://evil.example" + picture.path, picture.toURI().toString() + "?x=1", picture.toURI().toString() + "#x", "blocked-" + picture.toURI(),
            "android.resource://app.absentmotion/raw/a", "ftp://x/a", "file:", "file:media/p1/a.jpg")) {
            assertFalse(uri, access.allows(uri))
        }
    }

    @Test fun defusedKeepsWhatIsAllowedAndMakesTheRestUnopenable() {
        val picture = file("media/p1/a.jpg").toURI().toString()
        val secret = file("preferences/settings.json").toURI().toString()
        assertEquals(picture, access.defused(picture))
        assertEquals("content://media/external/images/media/12", access.defused("content://media/external/images/media/12"))
        assertEquals("blocked-amproj:a.jpg", access.defused("amproj:a.jpg"))
        assertEquals("blocked-https://example.com/a.mp4", access.defused("https://example.com/a.mp4"))
        assertEquals("blocked-/etc/hosts", access.defused("/etc/hosts"))
        assertEquals("blocked-x://elsewhere", access.defused("x://elsewhere"))
        assertEquals("blocked-file:///etc/hosts", access.defused("blocked-file:///etc/hosts".removePrefix("blocked-")))
        assertEquals("blocked-file:///etc/hosts", access.defused("blocked-file:///etc/hosts"))
        assertEquals("", access.defused(""))
        assertEquals("blocked-$secret", access.defused(secret))
        assertEquals("blocked-FILE:///etc/hosts", access.defused("FILE:///etc/hosts"))
        assertFalse(access.allows(access.defused(secret)))
    }

    @Test fun ownFoldersAreAllowedButNothingAboveThem() {
        val external = tmp.newFolder("external")
        val own = MediaAccess.forPhone(files, external)
        val tone = File(external, "qa/tone.wav").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(own.allows(tone.toURI().toString()))
        assertEquals(tone.toURI().toString(), own.defused(tone.toURI().toString()))
        assertFalse(access.allows(tone.toURI().toString()))
        assertFalse(own.allows(external.toURI().toString()))
        assertFalse(own.allows("file://" + external.path + "/../data/files/projects/p1.xml"))
        assertFalse(own.allows(file("projects/p1.xml").toURI().toString()))
    }

    @Test fun onlyContentAndFileAddressesCanBeOpened() {
        for (ok in listOf("content://media/external/video/media/1", "file:///data/x.mp4", "FILE:///data/x.mp4", "Content://a/b")) assertEquals(ok, openableUri(ok))
        for (bad in listOf("https://example.com/a.mp4", "http://example.com/a.mp4", "rtsp://x/y", "ftp://x/y", "/etc/hosts", "relative/path.mp4", "", "blocked-file:///x", "android.resource://a/1", "data:video/mp4;base64,AAAA", "x:y")) {
            try { openableUri(bad); org.junit.Assert.fail("opened $bad") } catch (_: SecurityException) {}
        }
    }

    @Test fun unlimitedAllowsEverythingForTheMac() {
        assertTrue(MediaAccess.Unlimited.allows("file:///anywhere/else/a.mp4"))
        assertTrue(MediaAccess.Unlimited.allows("testdata/media/test-image.jpg"))
        assertEquals("file:///etc/hosts", MediaAccess.Unlimited.defused("file:///etc/hosts"))
    }

    @Test fun onlyImportedFontsCanBePackedAndNothingAboveTheirFolder() {
        val font = file("fonts/imported/My Font.ttf")
        file("fonts/google/abc.ttf")
        assertEquals(font, access.importedFont("imported?name=My Font.ttf"))
        for (other in listOf("imported?name=../google/abc.ttf", "imported?name=..", "imported?name=", "imported?name=Gone.ttf", "googlefonts?name=Roboto&weight=400", "")) {
            assertEquals(other, null, access.importedFont(other))
        }
        assertEquals(null, MediaAccess.Unlimited.importedFont("imported?name=My Font.ttf"))
    }
}
