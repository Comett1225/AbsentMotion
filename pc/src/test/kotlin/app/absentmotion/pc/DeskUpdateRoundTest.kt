package app.absentmotion.pc

import app.absentmotion.ui.home.ApkAsset
import app.absentmotion.ui.home.FeedSource
import app.absentmotion.ui.home.Release
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateFailure
import app.absentmotion.ui.home.UpdateInstall
import app.absentmotion.ui.home.UpdateProblem
import app.absentmotion.ui.home.WhatsNewNotes
import app.absentmotion.ui.home.checksumOf
import app.absentmotion.ui.home.sweepUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeskUpdateRoundTest {
    private fun folder(): File = Files.createTempDirectory("am-round").toFile().also { it.deleteOnExit() }

    @Test fun aDesktopFileWithoutTheSignedListIsRefusedForEveryKind() {
        val source = FeedSource("http://127.0.0.1:1")
        val release = Release("5.0.3", "https://github.com/Comett1225/AbsentMotion/releases/tag/v5.0.3")
        for (name in listOf("AbsentMotion-5.0.3-windows-x64.msi", "AbsentMotion-5.0.3-macos-arm64.dmg", "AbsentMotion-5.0.3-linux-x86_64.deb")) {
            val apk = ApkAsset(name, source.asset("v5.0.3", name), 5000, null, source.asset("v5.0.3", "SHA256SUMS.txt"), null)
            val failure = (runCatching { checksumOf(TrustedHttp(source), release, apk) }.exceptionOrNull() as? UpdateProblem)?.failure
            assertEquals(UpdateFailure.Unsigned, failure)
        }
    }

    @Test fun aSymlinkedStagingFolderIsUnlinkedAndItsTargetKeepsItsFiles() {
        Assume.assumeTrue(!System.getProperty("os.name").lowercase().startsWith("windows"))
        val root = folder()
        val target = File(root, "precious").also { it.mkdirs() }
        File(target, "keep.txt").writeText("x")
        val link = File(root, ".absentmotion-update")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        UpdateInstall.clear(link)
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(target, "keep.txt").exists())
        val real = File(root, "real").also { File(it, "inner").mkdirs() }
        File(real, "inner/a.txt").writeText("x")
        UpdateInstall.clear(real)
        assertFalse(real.exists())
    }

    @Test fun anEmptyOrUnknownResultIsStillReportedAsAFailure() {
        val result = File(folder(), "update-result.txt")
        result.writeText("exit \n")
        assertEquals("installer code unknown", UpdateInstall.lastFailure(result))
        result.writeText("exit 0\n")
        assertNull(UpdateInstall.lastFailure(result))
        result.writeText("something else\n")
        assertEquals("unknown", UpdateInstall.lastFailure(result))
    }

    @Test fun oldDownloadsAndTheLogAreSweptButANewerFileStays() {
        val dir = folder()
        val old = File(dir, "AbsentMotion-5.0.2-windows-x64.msi").apply { writeText("x") }
        val same = File(dir, "AbsentMotion-5.0.3-windows-x64.msi").apply { writeText("x") }
        val newer = File(dir, "AbsentMotion-5.0.4-windows-x64.msi").apply { writeText("x") }
        val log = File(dir, "update-install.log").apply { writeText("x") }
        sweepUpdates(dir, "5.0.3", true)
        assertFalse(old.exists())
        assertFalse(same.exists())
        assertTrue(newer.exists())
        assertTrue(log.exists())
        sweepUpdates(dir, "5.0.3", false)
        assertFalse(log.exists())
    }

    @Test fun aPatchWithoutNotesDoesNotRepeatTheReleaseNotesWithinTheSameLine() {
        val files = setOf("5.0.0")
        assertNull(WhatsNewNotes.notesFor("5.0.4", "5.0.3") { it in files })
        assertEquals("5.0.0", WhatsNewNotes.notesFor("5.0.4", "4.4.1") { it in files })
        assertEquals("5.0.0", WhatsNewNotes.notesFor("5.0.4", null) { it in files })
    }
}
