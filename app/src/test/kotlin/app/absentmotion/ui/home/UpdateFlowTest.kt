package app.absentmotion.ui.home

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

class UpdateFlowTest {
    private val release = Release("4.1.4", HOME_PAGE, ApkAsset("AbsentMotion-4.1.4-foss.apk", "$HOME_PAGE/releases/download/v4.1.4/AbsentMotion-4.1.4-foss.apk", 1000, "a".repeat(64), null))
    private val downloading = UpdateStep.Downloading(release, 0, 1000)

    private fun run(vararg events: UpdateEvent, from: UpdateStep = UpdateStep.Idle) = events.fold(from, ::reduce)

    @Test fun aDownloadStartsOnlyWhenNothingElseIsRunning() {
        assertEquals(downloading, run(UpdateEvent.Start(release)))
        val busy = run(UpdateEvent.Start(release), UpdateEvent.Progress(400))
        assertSame(busy, reduce(busy, UpdateEvent.Start(release)))
        assertEquals(UpdateStep.Idle, run(UpdateEvent.Start(release.copy(apk = null))))
        val installing = UpdateStep.Installing(release)
        assertSame(installing, reduce(installing, UpdateEvent.Start(release)))
    }

    @Test fun progressIsClampedAndIgnoredOnceTheDownloadIsOver() {
        assertEquals(downloading.copy(done = 1000), run(UpdateEvent.Start(release), UpdateEvent.Progress(5000)))
        assertEquals(downloading.copy(done = 0), run(UpdateEvent.Start(release), UpdateEvent.Progress(-5)))
        assertEquals(UpdateStep.Idle, run(UpdateEvent.Start(release), UpdateEvent.Cancel, UpdateEvent.Progress(500)))
        assertEquals(UpdateStep.Idle, run(UpdateEvent.Start(release), UpdateEvent.Cancel, UpdateEvent.Downloaded))
    }

    @Test fun failuresCanBeRetriedOrClosed() {
        val failed = run(UpdateEvent.Start(release), UpdateEvent.Progress(300), UpdateEvent.Fail(UpdateFailure.Connection))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Connection), failed)
        assertEquals(downloading, reduce(failed, UpdateEvent.Start(release)))
        assertEquals(UpdateStep.Idle, reduce(failed, UpdateEvent.Cancel))
        assertEquals(UpdateStep.Idle, reduce(UpdateStep.Idle, UpdateEvent.Fail(UpdateFailure.Offline)))
        assertTrue(UpdateFailure.Connection.retry && UpdateFailure.Offline.retry && UpdateFailure.Storage(1).retry && UpdateFailure.Checksum.retry)
        assertFalse(UpdateFailure.Signature.retry || UpdateFailure.Untrusted.retry || UpdateFailure.WrongApp.retry || UpdateFailure.NotNewer.retry || UpdateFailure.NoInstaller.retry)
    }

    @Test fun theInstallWaitsForPermissionAndNeverLoops() {
        val asking = run(UpdateEvent.Start(release), UpdateEvent.Downloaded, UpdateEvent.NeedsPermission)
        assertEquals(UpdateStep.Permission(release, denied = false), asking)
        val denied = reduce(asking, UpdateEvent.SettingsClosed(granted = false))
        assertEquals(UpdateStep.Permission(release, denied = true), denied)
        assertEquals(denied, reduce(denied, UpdateEvent.SettingsClosed(granted = false)))
        assertEquals(UpdateStep.Installing(release), reduce(denied, UpdateEvent.SettingsClosed(granted = true)))
        assertEquals(UpdateStep.Idle, reduce(denied, UpdateEvent.Cancel))
        assertEquals(UpdateStep.Idle, reduce(UpdateStep.Idle, UpdateEvent.SettingsClosed(granted = true)))
        assertTrue(needsAttention(asking) && needsAttention(denied))
    }

    @Test fun theInstallerAnswerDecidesWhatComesNext() {
        val installing = run(UpdateEvent.Start(release), UpdateEvent.Downloaded, UpdateEvent.Install)
        assertEquals(UpdateStep.Installing(release), installing)
        assertEquals(installing, reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_PENDING_USER_ACTION)))
        assertEquals(UpdateStep.Idle, reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_SUCCESS)))
        val notInstalled = reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(UpdateStep.NotInstalled(release), notInstalled)
        assertEquals(UpdateStep.Installing(release), reduce(notInstalled, UpdateEvent.Install))
        assertEquals(UpdateStep.Permission(release, false), reduce(notInstalled, UpdateEvent.NeedsPermission))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Signature), reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE_CONFLICT)))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Storage(installSpace(1000))), reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE_STORAGE)))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.WrongApp), reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.InstallFailed), reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE_BLOCKED)))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.InstallFailed), reduce(installing, UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE)))
        assertEquals(downloading, reduce(notInstalled, UpdateEvent.Start(release)))
    }

    @Test fun lateInstallerAnswersCannotRestartAClosedFlow() {
        for (status in listOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE)) {
            assertEquals(UpdateStep.Idle, reduce(UpdateStep.Idle, UpdateEvent.Answer(status)))
            assertEquals(downloading, reduce(downloading, UpdateEvent.Answer(status)))
        }
    }

    @Test fun aRestoredDownloadIsCheckedBeforeAnythingElse() {
        assertEquals(UpdateStep.Checking(release), run(UpdateEvent.Resume(release)))
        assertSame(downloading, reduce(downloading, UpdateEvent.Resume(release)))
        assertEquals(UpdateStep.Permission(release, true), run(UpdateEvent.Resume(release), UpdateEvent.NeedsPermission, UpdateEvent.SettingsClosed(false)))
        assertEquals(UpdateStep.Installing(release), run(UpdateEvent.Resume(release), UpdateEvent.Install))
    }

    @Test fun installerStatusesMapToClearFailures() {
        assertEquals(null, installerFailure(PackageInstaller.STATUS_FAILURE_ABORTED, 5))
        assertEquals(UpdateFailure.Storage(5), installerFailure(PackageInstaller.STATUS_FAILURE_STORAGE, 5))
        assertEquals(UpdateFailure.WrongApp, installerFailure(PackageInstaller.STATUS_FAILURE_INVALID, 5))
    }

    @Test fun aDownloadLostWhileInSettingsOffersTryAgainAndNeverInstallsByItself() {
        val lost = run(UpdateEvent.Resume(release), UpdateEvent.Fail(UpdateFailure.Removed))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Removed), lost)
        assertTrue(needsAttention(lost))
        assertTrue(UpdateFailure.Removed.retry)
        assertSame(lost, reduce(lost, UpdateEvent.Install))
        assertSame(lost, reduce(lost, UpdateEvent.SettingsClosed(granted = true)))
        assertSame(lost, reduce(lost, UpdateEvent.Answer(PackageInstaller.STATUS_SUCCESS)))
        assertEquals(downloading, reduce(lost, UpdateEvent.Start(release)))
        assertEquals(UpdateStep.Idle, reduce(lost, UpdateEvent.Cancel))
        val asking = run(UpdateEvent.Start(release), UpdateEvent.Downloaded, UpdateEvent.NeedsPermission)
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Removed), reduce(asking, UpdateEvent.Fail(UpdateFailure.Removed)))
        val installing = run(UpdateEvent.Start(release), UpdateEvent.Downloaded, UpdateEvent.Install)
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Removed), reduce(installing, UpdateEvent.Fail(UpdateFailure.Removed)))
        assertEquals(UpdateStep.Idle, reduce(UpdateStep.Idle, UpdateEvent.Fail(UpdateFailure.Removed)))
    }

    @Test fun theInstallPromptIsReadTypedOnlyFromAndroid14AndAlwaysTypeChecked() {
        for (sdk in 28..33) assertFalse("API $sdk", typedExtrasWork(sdk))
        for (sdk in 34..37) assertTrue("API $sdk", typedExtrasWork(sdk))
        assertEquals("prompt", ofType(String::class.java, "prompt"))
        assertNull(ofType(android.content.Intent::class.java, "not an intent"))
        assertNull(ofType(android.content.Intent::class.java, null))
        val text = StringBuilder("x")
        assertSame(text, ofType(CharSequence::class.java, text))
    }

    private val asking = UpdateStep.Permission(release, denied = false, downloaded = false)

    @Test fun choosingToUpdateAsksForInstallPermissionBeforeAnyDownload() {
        assertEquals(asking, begin(UpdateStep.Idle, release, mayInstall = false))
        for (event in listOf(UpdateEvent.Progress(500), UpdateEvent.Downloaded, UpdateEvent.Install, UpdateEvent.NeedsPermission,
            UpdateEvent.Answer(PackageInstaller.STATUS_SUCCESS), UpdateEvent.Answer(PackageInstaller.STATUS_FAILURE_ABORTED), UpdateEvent.Resume(release))) {
            assertSame(asking, reduce(asking, event))
        }
        assertEquals(UpdateStep.Idle, begin(UpdateStep.Idle, release.copy(apk = null), mayInstall = false))
        assertTrue(needsAttention(asking))
    }

    @Test fun aDeniedPermissionNeverDownloadsAndCanBeCancelled() {
        val denied = reduce(asking, UpdateEvent.SettingsClosed(granted = false))
        assertEquals(asking.copy(denied = true), denied)
        assertEquals(denied, reduce(denied, UpdateEvent.SettingsClosed(granted = false)))
        assertEquals(UpdateStep.Idle, reduce(denied, UpdateEvent.Cancel))
        assertEquals(UpdateStep.Idle, reduce(asking, UpdateEvent.Cancel))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.NoSettings), reduce(asking, UpdateEvent.Fail(UpdateFailure.NoSettings)))
    }

    @Test fun grantingInSettingsStartsTheDownloadNotTheInstall() {
        assertEquals(downloading, reduce(asking, UpdateEvent.SettingsClosed(granted = true)))
        assertEquals(downloading, run(UpdateEvent.SettingsClosed(granted = false), UpdateEvent.SettingsClosed(granted = true), from = asking))
        val installing = run(UpdateEvent.SettingsClosed(granted = true), UpdateEvent.Progress(1000), UpdateEvent.Downloaded, UpdateEvent.Install, from = asking)
        assertEquals(UpdateStep.Installing(release), installing)
    }

    @Test fun aDeviceThatAlreadyAllowsInstallsIsNeverAsked() {
        assertEquals(downloading, begin(UpdateStep.Idle, release, mayInstall = true))
        val path = listOf(UpdateEvent.Progress(1000), UpdateEvent.Downloaded, UpdateEvent.Install).runningFold(downloading as UpdateStep, ::reduce)
        assertFalse(path.any { it is UpdateStep.Permission })
        assertEquals(UpdateStep.Installing(release), path.last())
    }

    @Test fun permissionIsCheckedAgainBeforeTheInstall() {
        val revoked = run(UpdateEvent.Progress(1000), UpdateEvent.Downloaded, UpdateEvent.NeedsPermission, from = begin(UpdateStep.Idle, release, mayInstall = true))
        assertEquals(UpdateStep.Permission(release, denied = false, downloaded = true), revoked)
        assertEquals(UpdateStep.Installing(release), reduce(revoked, UpdateEvent.SettingsClosed(granted = true)))
        assertEquals(UpdateStep.Installing(release), reduce(revoked, UpdateEvent.Install))
    }

    @Test fun aRunningOrWaitingFlowIsNotAskedTwice() {
        val failed = UpdateStep.Failed(release, UpdateFailure.Connection)
        assertEquals(asking, begin(failed, release, mayInstall = false))
        assertEquals(downloading, begin(failed, release, mayInstall = true))
        for (busy in listOf(asking, asking.copy(denied = true), downloading, UpdateStep.Checking(release), UpdateStep.Installing(release))) {
            assertSame(busy, begin(busy, release, mayInstall = false))
            assertSame(busy, begin(busy, release, mayInstall = true))
        }
    }

    @Test fun aRestartWhileInSettingsComesBackToTheSameQuestion() {
        assertEquals(asking.copy(denied = true), run(UpdateEvent.Ask(release), UpdateEvent.SettingsClosed(granted = false)))
        assertEquals(downloading, run(UpdateEvent.Ask(release), UpdateEvent.SettingsClosed(granted = true)))
    }

    @Test fun anUpdateStoppedByTheAppClosingIsOfferedAgainOnlyFromIdle() {
        val stopped = run(UpdateEvent.Stopped(release))
        assertEquals(UpdateStep.Failed(release, UpdateFailure.Interrupted), stopped)
        assertTrue(UpdateFailure.Interrupted.retry)
        assertTrue(needsAttention(stopped))
        assertEquals(UpdateStep.Idle, run(UpdateEvent.Stopped(release.copy(apk = null))))
        assertSame(downloading, reduce(downloading, UpdateEvent.Stopped(release)))
        val asking = UpdateStep.Permission(release, denied = false, downloaded = false)
        assertSame(asking, reduce(asking, UpdateEvent.Stopped(release)))
        assertEquals(UpdateStep.Permission(release, denied = false, downloaded = false), begin(stopped, release, mayInstall = false))
        assertEquals(downloading, begin(stopped, release, mayInstall = true))
        assertEquals(UpdateStep.Idle, reduce(stopped, UpdateEvent.Cancel))
    }

    @Test fun aReconnectIsShownUntilBytesMoveAgain() {
        val waiting = run(UpdateEvent.Start(release), UpdateEvent.Progress(300), UpdateEvent.Waiting)
        assertEquals(downloading.copy(done = 300, waiting = true), waiting)
        assertEquals(downloading.copy(done = 300), reduce(waiting, UpdateEvent.Progress(300)))
        assertEquals(downloading.copy(done = 500), reduce(waiting, UpdateEvent.Progress(500)))
        assertEquals(UpdateStep.Checking(release), reduce(waiting, UpdateEvent.Downloaded))
        assertEquals(UpdateStep.Idle, reduce(UpdateStep.Idle, UpdateEvent.Waiting))
        val failed = UpdateStep.Failed(release, UpdateFailure.Connection)
        assertSame(failed, reduce(failed, UpdateEvent.Waiting))
    }

    @Test fun theUnfinishedUpdateIsForgottenOnlyWhenClosedOrRefusedForGood() {
        assertTrue(flowOver(UpdateStep.Idle))
        for (failure in listOf(UpdateFailure.Signature, UpdateFailure.WrongApp, UpdateFailure.NotNewer, UpdateFailure.NoChecksum, UpdateFailure.Untrusted)) {
            assertTrue(failure.toString(), flowOver(UpdateStep.Failed(release, failure)))
        }
        for (failure in listOf(UpdateFailure.Connection, UpdateFailure.Offline, UpdateFailure.Storage(1), UpdateFailure.Interrupted, UpdateFailure.Removed, UpdateFailure.Server(503))) {
            assertFalse(failure.toString(), flowOver(UpdateStep.Failed(release, failure)))
        }
        for (step in listOf(downloading, UpdateStep.Checking(release), UpdateStep.Installing(release), UpdateStep.NotInstalled(release), UpdateStep.Permission(release, denied = true))) {
            assertFalse(step.toString(), flowOver(step))
        }
    }

    @Test fun aRestartedDownloadWaitsForTheCancelledOneAndKeepsItsPartFile() {
        val dir = Files.createTempDirectory("update-restart").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val part = File(dir, "update.apk.part")
                val writing = CompletableDeferred<Unit>()
                val first = scope.launchAfter(null) {
                    try {
                        part.writeText("old")
                        writing.complete(Unit)
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) { delay(300); part.delete() }
                        throw e
                    }
                }
                writing.await()
                first.cancel()
                val tail = scope.launchAfter(first) { withContext(Dispatchers.IO) { part.delete() } }
                val second = scope.launchAfter(tail) { part.writeText("new") }
                second.join()
                assertTrue(first.isCompleted && tail.isCompleted)
                assertEquals("new", part.readText())
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
