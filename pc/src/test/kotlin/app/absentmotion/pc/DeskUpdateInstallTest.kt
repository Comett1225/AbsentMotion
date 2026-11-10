package app.absentmotion.pc

import app.absentmotion.ui.home.InstallKind
import app.absentmotion.ui.home.DEB_INSTALL
import app.absentmotion.ui.home.InstallSite
import app.absentmotion.ui.home.POSIX_SWAP
import app.absentmotion.ui.home.Release
import app.absentmotion.ui.home.UpdateInstall
import app.absentmotion.ui.home.windowsCommand
import app.absentmotion.ui.home.windowsScript
import app.absentmotion.ui.home.installSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeskUpdateInstallTest {
    private val posix = !System.getProperty("os.name").lowercase().startsWith("windows")

    private fun folder(): File = Files.createTempDirectory("am-update").toFile().also { it.deleteOnExit() }

    private fun script(dir: File) = File(dir, "swap.sh").apply { writeText(POSIX_SWAP); setExecutable(true) }

    private fun waitFor(file: File, ms: Long = 8000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (file.exists()) return true; Thread.sleep(50) }
        return file.exists()
    }

    private fun appFolder(root: File, name: String, marker: String, reports: File = root) = File(root, name).also { dir ->
        File(dir, "bin").mkdirs()
        File(dir, "bin/absentmotion.sh").apply { writeText("#!/bin/sh\necho started-$marker > \"${reports.path}/launched.txt\"\n"); setExecutable(true) }
        File(dir, "install.sh").apply { writeText("#!/bin/sh\necho refreshed > \"${reports.path}/refreshed.txt\"\n"); setExecutable(true) }
        File(dir, "version.txt").writeText(marker)
    }

    private fun running(): Process = ProcessBuilder("sleep", "1").start()

    @Test fun aFolderIsSwappedAfterTheAppExitsAndTheNewOneStarts() {
        Assume.assumeTrue(posix)
        val root = folder()
        val target = appFolder(root, "installed", "old")
        val staging = File(root, "staging").also { it.mkdirs() }
        val staged = appFolder(staging, "AbsentMotion", "new", root)
        val result = File(root, "result.txt")
        val app = running()
        val started = System.currentTimeMillis()
        ProcessBuilder("/bin/sh", script(root).path, app.pid().toString(), staged.path, target.path, "tarball", result.path, staging.path).start()
        Thread.sleep(300)
        assertEquals("old", File(target, "version.txt").readText())
        assertTrue(waitFor(result))
        assertTrue(waitFor(File(root, "launched.txt")))
        assertTrue("it waited for the app to close", System.currentTimeMillis() - started > 700)
        assertEquals("ok", result.readText().trim())
        assertEquals("new", File(target, "version.txt").readText())
        assertEquals("started-new", File(root, "launched.txt").readText().trim())
        assertTrue(File(root, "refreshed.txt").exists())
        assertFalse(File(root, "installed.previous").exists())
        assertFalse(staging.exists())
    }

    @Test fun everyProgramRunningFromTheAppIsClosedBeforeTheSwapAndOthersAreLeftAlone() {
        Assume.assumeTrue(posix)
        val root = folder()
        val source = File(root, "hold.c").apply { writeText("#include <unistd.h>\nint main(void) { for (;;) sleep(1); }\n") }
        val compiled = File(root, "hold")
        val cc = runCatching { ProcessBuilder("cc", "-o", compiled.path, source.path).start().waitFor() }.getOrNull()
        Assume.assumeTrue(cc == 0 && compiled.canExecute())
        val target = appFolder(root, "installed", "old")
        val inside = File(target, "bin/hold").also { compiled.copyTo(it); it.setExecutable(true) }
        val outside = File(root, "installed-other/hold").also { it.parentFile.mkdirs(); compiled.copyTo(it); it.setExecutable(true) }
        val second = ProcessBuilder(inside.path).start()
        val unrelated = ProcessBuilder(outside.path).start()
        try {
            val staging = File(root, "staging").also { it.mkdirs() }
            val staged = appFolder(staging, "AbsentMotion", "new", root)
            val result = File(root, "result.txt")
            ProcessBuilder("/bin/sh", script(root).path, running().pid().toString(), staged.path, target.path, "tarball", result.path, staging.path).start()
            assertTrue(waitFor(File(root, "launched.txt")))
            assertEquals("ok", result.readText().trim())
            assertFalse("the second copy running from the app was closed", second.isAlive)
            assertTrue("a program outside the app was left running", unrelated.isAlive)
        } finally {
            second.destroyForcibly()
            unrelated.destroyForcibly()
        }
    }

    @Test fun anUpdateThatStoppedHalfWayOrNeverStartedIsReported() {
        assertEquals("the updater was stopped", UpdateInstall.failureOf("started\n"))
        assertEquals("the updater was stopped", UpdateInstall.failureOf("started\nstage swap\n"))
        assertNull(UpdateInstall.failureOf("started\nok\n"))
        assertNull(UpdateInstall.failureOf("started\nok 5.0.6\n"))
        assertEquals("files were in use", UpdateInstall.failureOf("started\nfail files were in use\n"))
        assertEquals("the updater was stopped", app.absentmotion.ui.home.updateOutcome(null, "5.0.6", "5.0.5"))
        assertNull(app.absentmotion.ui.home.updateOutcome(null, "5.0.6", "5.0.6"))
        assertNull(app.absentmotion.ui.home.updateOutcome(null, null, "5.0.5"))
        assertEquals("the app did not close", app.absentmotion.ui.home.updateOutcome("started\nfail the app did not close", "5.0.6", "5.0.5"))
        assertEquals("the update helper was blocked", UpdateInstall.startFailure("Cannot run program \"x\": CreateProcess error=225, Operation did not complete successfully because the file contains a virus"))
        assertEquals("the update helper was blocked", UpdateInstall.startFailure("CreateProcess error=4551, An Application Control policy has blocked this file"))
        assertEquals("the helper did not start", UpdateInstall.startFailure("CreateProcess error=193, %1 is not a valid Win32 application"))
    }

    @Test fun aFileIsSwappedLikeAnAppImage() {
        Assume.assumeTrue(posix)
        val root = folder()
        val target = File(root, "AbsentMotion.AppImage").apply { writeText("#!/bin/sh\necho old > \"${root.path}/launched.txt\"\n"); setExecutable(true) }
        val staged = File(root, "AbsentMotion.AppImage.new").apply { writeText("#!/bin/sh\necho new > \"${root.path}/launched.txt\"\n"); setExecutable(true) }
        val result = File(root, "result.txt")
        ProcessBuilder("/bin/sh", script(root).path, running().pid().toString(), staged.path, target.path, "appimage", result.path, "").start()
        assertTrue(waitFor(File(root, "launched.txt")))
        assertEquals("new", File(root, "launched.txt").readText().trim())
        assertEquals("ok", result.readText().trim())
        assertFalse(staged.exists())
    }

    @Test fun aFailedSwapPutsTheOldVersionBack() {
        Assume.assumeTrue(posix)
        val root = folder()
        val target = appFolder(root, "installed", "old")
        val result = File(root, "result.txt")
        ProcessBuilder("/bin/sh", script(root).path, "999999", File(root, "missing").path, target.path, "tarball", result.path, "").start()
        assertTrue(waitFor(File(root, "launched.txt")))
        assertTrue(result.readText().startsWith("fail"))
        assertEquals("old", File(target, "version.txt").readText())
        assertEquals("started-old", File(root, "launched.txt").readText().trim())
        assertFalse(File(root, "installed.previous").exists())
    }

    @Test fun anAppThatNeverClosesIsNotReplaced() {
        Assume.assumeTrue(posix)
        val root = folder()
        val target = appFolder(root, "installed", "old")
        val staged = appFolder(File(root, "s").also { it.mkdirs() }, "AbsentMotion", "new", root)
        val result = File(root, "result.txt")
        val stuck = ProcessBuilder("sleep", "5").start()
        val swap = File(root, "short.sh").apply { writeText(POSIX_SWAP.replace("6000", "10")); setExecutable(true) }
        val run = ProcessBuilder("/bin/sh", swap.path, stuck.pid().toString(), staged.path, target.path, "tarball", result.path, "").start()
        assertTrue(run.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(result.readText().startsWith("fail"))
        assertEquals("old", File(target, "version.txt").readText())
        stuck.destroyForcibly()
    }

    @Test fun theLinuxInstallSiteFollowsHowTheAppWasInstalled() {
        Assume.assumeTrue(posix)
        val home = folder()
        val opt = File(home, ".local/opt/absentmotion/lib/runtime").also { it.mkdirs() }
        assertEquals(InstallKind.Tarball, installSite("Linux", home.path, opt.path, null, null)!!.kind)
        assertEquals(File(home, ".local/opt/absentmotion"), installSite("Linux", home.path, opt.path, null, null)!!.target)
        assertEquals(InstallKind.Deb, installSite("Linux", home.path, "/opt/absentmotion/lib/runtime", null, null, null, true)!!.kind)
        assertNull(installSite("Linux", home.path, "/opt/absentmotion/lib/runtime", null, null, null, false))
        val image = File(home, "AbsentMotion.AppImage").apply { writeText("x") }
        assertEquals(InstallKind.AppImage, installSite("Linux", home.path, "/tmp/.mount_x/lib/runtime", null, image.path, "/tmp/.mount_x")!!.kind)
        assertEquals(InstallKind.Tarball, installSite("Linux", home.path, opt.path, null, image.path, "/tmp/.mount_other", false)!!.kind)
        assertEquals(InstallKind.Deb, installSite("Linux", home.path, "/opt/absentmotion/lib/runtime", null, image.path, null, true)!!.kind)
        assertNull(installSite("Linux", home.path, "/tmp/elsewhere/runtime", null, null))
    }

    @Test fun theWindowsInstallSiteNeedsTheLauncherPath() {
        val home = folder()
        val launcher = File(home, "AbsentMotion.exe").apply { writeText("x") }
        assertEquals(InstallKind.Msi, installSite("Windows 11", home.path, "C:\\x", launcher.path, null)!!.kind)
        assertNull(installSite("Windows 11", home.path, "C:\\x", null, null))
    }

    @Test fun aMacAppInAWritableFolderIsReplacedInPlaceButATranslocatedOneIsNot() {
        val apps = folder()
        val bundle = File(apps, "AbsentMotion.app/Contents/MacOS").also { it.mkdirs() }
        val executable = File(bundle, "AbsentMotion").path
        val site = installSite("Mac OS X", "/home/x", "/x", executable, null)
        assertEquals(InstallKind.MacApp, site!!.kind)
        assertEquals(File(apps, "AbsentMotion.app"), site.target)
        val moved = File(apps, "AppTranslocation/ABC/d/AbsentMotion.app/Contents/MacOS").also { it.mkdirs() }
        assertNull(installSite("Mac OS X", "/home/x", "/x", File(moved, "AbsentMotion").path, null))
    }

    @Test fun onlyTheFileThatMatchesTheInstallIsInstalledInPlace() {
        val tar = installSite("Linux", "/home/x", "/home/x/.local/opt/absentmotion/lib", null, null)
        val folderOnly = File("/home/x/.local/opt/absentmotion")
        assertNull(tar)
        val site = app.absentmotion.ui.home.InstallSite(InstallKind.Tarball, folderOnly)
        assertTrue(UpdateInstall.supports(File("AbsentMotion-5.0.3-linux-x86_64.tar.gz"), site))
        assertFalse(UpdateInstall.supports(File("AbsentMotion-5.0.3-linux-x86_64.deb"), site))
        assertFalse(UpdateInstall.supports(File("AbsentMotion-5.0.3-windows-x64.msi"), site))
        assertTrue(UpdateInstall.supports(File("AbsentMotion-5.0.3-macos-arm64-update.zip"), app.absentmotion.ui.home.InstallSite(InstallKind.MacApp, folderOnly)))
        assertFalse(UpdateInstall.supports(File("AbsentMotion-5.0.3-macos-arm64.dmg"), app.absentmotion.ui.home.InstallSite(InstallKind.MacApp, folderOnly)))
        assertFalse(UpdateInstall.supports(File("a.msi"), null))
    }

    @Test fun theLastResultIsReportedOnceAndOnlyWhenItFailed() {
        val dir = folder()
        val result = File(dir, "update-result.txt")
        assertNull(UpdateInstall.lastFailure(result))
        result.writeText("ok\n")
        assertNull(UpdateInstall.lastFailure(result))
        assertFalse(result.exists())
        result.writeText("exit 3010\n")
        assertNull(UpdateInstall.lastFailure(result))
        result.writeText("exit 1603\n")
        assertEquals("installer code 1603", UpdateInstall.lastFailure(result))
        assertNull(UpdateInstall.lastFailure(result))
        result.writeText("fail the app did not close\n")
        assertEquals("the app did not close", UpdateInstall.lastFailure(result))
    }

    private fun release(version: String = "5.0.3") = Release(version, "https://github.com/Comett1225/AbsentMotion/releases/tag/v$version")

    @Test fun aTarballIsUnpackedChecksAndSwappedThroughThePreparedCommand() {
        Assume.assumeTrue(posix)
        val root = folder()
        val source = File(root, "source/AbsentMotion").also { File(it, "bin").mkdirs() }
        File(source, "bin/AbsentMotion").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        File(source, "bin/absentmotion.sh").apply { writeText("#!/bin/sh\necho started-new > \"${root.path}/launched.txt\"\n"); setExecutable(true) }
        File(source, "install.sh").apply { writeText("#!/bin/sh\necho refreshed > \"${root.path}/refreshed.txt\"\n"); setExecutable(true) }
        File(source, "version.txt").writeText("new")
        val tarball = File(root, "AbsentMotion-5.0.3-linux-x86_64.tar.gz")
        assertEquals(0, ProcessBuilder("tar", "-czf", tarball.path, "-C", File(root, "source").path, "AbsentMotion").start().waitFor())
        val opt = File(root, "opt").also { it.mkdirs() }
        val target = appFolder(opt, "absentmotion", "old", root)
        val work = File(root, "work")
        val result = File(root, "result.txt")
        val pending = UpdateInstall.prepare(tarball, release(), InstallSite(InstallKind.Tarball, target), work, result, 999999L)
        assertTrue(File(opt, ".absentmotion-update/AbsentMotion/bin/AbsentMotion").exists())
        assertEquals("old", File(target, "version.txt").readText())
        ProcessBuilder(pending.command).start()
        assertTrue(waitFor(File(root, "launched.txt")))
        assertEquals("new", File(target, "version.txt").readText())
        assertEquals("ok", result.readText().trim())
        assertTrue(File(root, "refreshed.txt").exists())
        assertFalse(File(opt, ".absentmotion-update").exists())
    }

    @Test fun aTarballThatIsNotAnAbsentMotionFolderIsRefusedBeforeAnythingChanges() {
        Assume.assumeTrue(posix)
        val root = folder()
        File(root, "source/Other").also { it.mkdirs() }.let { File(it, "x.txt").writeText("x") }
        val tarball = File(root, "AbsentMotion-5.0.3-linux-x86_64.tar.gz")
        assertEquals(0, ProcessBuilder("tar", "-czf", tarball.path, "-C", File(root, "source").path, "Other").start().waitFor())
        val opt = File(root, "opt").also { it.mkdirs() }
        val target = appFolder(opt, "absentmotion", "old", root)
        val failed = runCatching { UpdateInstall.prepare(tarball, release(), InstallSite(InstallKind.Tarball, target), File(root, "work"), File(root, "result.txt"), 1L) }
        assertTrue(failed.isFailure)
        assertEquals("old", File(target, "version.txt").readText())
        assertFalse(File(opt, ".absentmotion-update").exists())
    }

    @Test fun anAppImageIsCopiedBesideTheOldOneAndSwapped() {
        Assume.assumeTrue(posix)
        val root = folder()
        val target = File(root, "AbsentMotion.AppImage").apply { writeText("#!/bin/sh\necho old > \"${root.path}/launched.txt\"\n"); setExecutable(true) }
        val download = File(root, "dl/AbsentMotion-5.0.3-linux-x86_64.AppImage").apply { parentFile.mkdirs(); writeText("#!/bin/sh\necho new > \"${root.path}/launched.txt\"\n") }
        val result = File(root, "result.txt")
        val pending = UpdateInstall.prepare(download, release(), InstallSite(InstallKind.AppImage, target), File(root, "work"), result, 999999L)
        assertTrue(File(root, "AbsentMotion.AppImage.new").canExecute())
        ProcessBuilder(pending.command).start()
        assertTrue(waitFor(File(root, "launched.txt")))
        assertEquals("new", File(root, "launched.txt").readText().trim())
        assertEquals("ok", result.readText().trim())
        assertFalse(File(root, "AbsentMotion.AppImage.new").exists())
    }

    @Test fun aDebIsInstalledThroughPkexecThenTheAppStartsAgain() {
        Assume.assumeTrue(posix)
        val root = folder()
        val bin = File(root, "bin").also { it.mkdirs() }
        File(bin, "pkexec").apply { writeText("#!/bin/sh\necho \"\$@\" > \"${root.path}/pkexec.txt\"\n"); setExecutable(true) }
        val installed = File(root, "opt/bin").also { it.mkdirs() }
        File(installed, "AbsentMotion").apply { writeText("#!/bin/sh\necho started > \"${root.path}/launched.txt\"\n"); setExecutable(true) }
        val script = File(root, "deb.sh").apply { writeText(DEB_INSTALL); setExecutable(true) }
        val result = File(root, "result.txt")
        val builder = ProcessBuilder("/bin/sh", script.path, "999999", "/tmp/AbsentMotion-5.0.3-linux-x86_64.deb", result.path, File(installed, "AbsentMotion").path)
        builder.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        builder.start()
        assertTrue(waitFor(File(root, "launched.txt")))
        assertEquals("ok", result.readText().trim())
        assertEquals("apt-get -o DPkg::Lock::Timeout=120 install -y /tmp/AbsentMotion-5.0.3-linux-x86_64.deb", File(root, "pkexec.txt").readText().trim())
    }

    @Test fun aRefusedDebStillStartsTheOldApp() {
        Assume.assumeTrue(posix)
        val root = folder()
        val bin = File(root, "bin").also { it.mkdirs() }
        File(bin, "pkexec").apply { writeText("#!/bin/sh\nexit 126\n"); setExecutable(true) }
        val installed = File(root, "opt/bin").also { it.mkdirs() }
        File(installed, "AbsentMotion").apply { writeText("#!/bin/sh\necho old > \"${root.path}/launched.txt\"\n"); setExecutable(true) }
        val script = File(root, "deb.sh").apply { writeText(DEB_INSTALL); setExecutable(true) }
        val result = File(root, "result.txt")
        val builder = ProcessBuilder("/bin/sh", script.path, "999999", "/tmp/x.deb", result.path, File(installed, "AbsentMotion").path)
        builder.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        builder.start()
        assertTrue(waitFor(File(root, "launched.txt")))
        assertTrue(result.readText().startsWith("fail"))
        assertEquals("old", File(root, "launched.txt").readText().trim())
    }

    @Test fun theWindowsScriptCarriesItsValuesQuotedAndTravelsAsAnEncodedCommand() {
        val script = windowsScript(4321L, "C:\\Users\\O'Neil\\a b\\AbsentMotion-5.0.3.msi", "C:\\Apps\\AbsentMotion.exe", "C:\\r\\update-result.txt", "C:\\r\\log.txt")
        assertTrue(script.contains("\$pidToWait = 4321"))
        assertTrue(script.contains("'C:\\Users\\O''Neil\\a b\\AbsentMotion-5.0.3.msi'"))
        assertTrue(script.contains("/passive") && script.contains("/norestart"))
        assertTrue(script.contains("('INSTALLDIR=\"' + \$folder + '\"')"))
        assertTrue(script.contains("Split-Path -Parent \$launcher"))
        assertTrue(script.contains("Uninstall") && script.contains("InstallLocation"))
        val command = windowsCommand(script)
        assertEquals("powershell.exe", command.first())
        assertEquals("-EncodedCommand", command[command.size - 2])
        val decoded = String(java.util.Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
        assertEquals(script, decoded)
        assertTrue(command.last().length < 20_000)
        assertFalse(command.any { it.contains("update-apply.ps1") })
    }
}
