package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVerifyTest {
    private val key = "5faf3479" + "0".repeat(56)
    private val other = "27acf757" + "0".repeat(56)
    private val installed = InstalledApp("app.absentmotion", 40, "4.1.3", "foss", setOf(key))
    private val good = ApkFacts("app.absentmotion", 41, "4.1.4", "foss", setOf(key), setOf(key))

    @Test fun theMatchingNewerSameKeyApkPasses() {
        assertNull(judgeApk(good, installed, "4.1.4"))
    }

    @Test fun anotherAppOrAnotherVariantIsRefused() {
        assertEquals(UpdateFailure.WrongApp, judgeApk(null, installed, "4.1.4"))
        assertEquals(UpdateFailure.WrongApp, judgeApk(good.copy(packageName = "app.absentmotion.fake"), installed, "4.1.4"))
        assertEquals(UpdateFailure.WrongApp, judgeApk(good.copy(variant = "play"), installed, "4.1.4"))
        assertEquals(UpdateFailure.WrongApp, judgeApk(good.copy(variant = "compat"), installed, "4.1.4"))
        assertEquals(UpdateFailure.WrongApp, judgeApk(good.copy(variant = null), installed, "4.1.4"))
        assertNull(judgeApk(good.copy(variant = "compat"), installed.copy(variant = "compat"), "4.1.4"))
    }

    @Test fun aDifferentSigningKeyIsRefused() {
        assertEquals(UpdateFailure.Signature, judgeApk(good.copy(signers = setOf(other), history = setOf(other)), installed, "4.1.4"))
        assertEquals(UpdateFailure.Signature, judgeApk(good.copy(signers = emptySet()), installed, "4.1.4"))
        assertEquals(UpdateFailure.Signature, judgeApk(good.copy(signers = setOf(key, other)), installed, "4.1.4"))
        assertEquals(UpdateFailure.Signature, judgeApk(good, installed.copy(signers = emptySet()), "4.1.4"))
        assertEquals(UpdateFailure.Signature, judgeApk(good.copy(signers = setOf(other), history = setOf(other), variant = null, versionCode = 39), installed, "4.1.4"))
    }

    @Test fun aRotatedKeyThatProvesTheInstalledKeyPasses() {
        assertTrue(signersMatch(setOf(key), setOf(other), setOf(key, other)))
        assertFalse(signersMatch(setOf(key), setOf(other), setOf(other)))
        assertFalse(signersMatch(setOf(key, other), setOf(other), setOf(key, other)))
        assertNull(judgeApk(good.copy(signers = setOf(other), history = setOf(key, other)), installed, "4.1.4"))
    }

    @Test fun onlyAReallyNewerVersionOfTheAnnouncedReleasePasses() {
        assertEquals(UpdateFailure.NotNewer, judgeApk(good.copy(versionName = "4.1.5"), installed, "4.1.4"))
        assertEquals(UpdateFailure.NotNewer, judgeApk(good.copy(versionCode = 40), installed, "4.1.4"))
        assertEquals(UpdateFailure.NotNewer, judgeApk(good.copy(versionCode = 39), installed, "4.1.4"))
        assertEquals(UpdateFailure.NotNewer, judgeApk(good.copy(versionName = "4.1.3"), installed, "4.1.3"))
        assertEquals(UpdateFailure.NotNewer, judgeApk(good.copy(versionName = null), installed, "4.1.4"))
        assertEquals(UpdateFailure.NotNewer, judgeApk(good.copy(versionName = "4.1.2"), installed, "4.1.2"))
        assertNull(judgeApk(good.copy(versionName = "4.1.4-test"), installed, "4.1.4-test"))
    }

    @Test fun onlyCopiesSignedWithTheReleaseKeyUpdateThemselvesFromGitHub() {
        val release = GitHubSource.signers
        assertEquals(setOf(RELEASE_SIGNER), release)
        assertTrue(isSha256(RELEASE_SIGNER))
        assertTrue(updatesInApp(null, setOf(RELEASE_SIGNER), setOf(RELEASE_SIGNER), release))
        assertTrue(updatesInApp("com.android.packageinstaller", setOf(RELEASE_SIGNER), emptySet(), release))
        assertTrue(updatesInApp("org.fdroid.fdroid", setOf(RELEASE_SIGNER), emptySet(), release))
        assertFalse(updatesInApp(null, setOf(other), emptySet(), release))
        assertFalse(updatesInApp(null, emptySet(), emptySet(), release))
        assertFalse(updatesInApp(null, setOf(RELEASE_SIGNER, other), emptySet(), release))
        assertTrue(updatesInApp(null, setOf(other), setOf(RELEASE_SIGNER, other), release))
        assertFalse(updatesInApp("com.android.vending", setOf(RELEASE_SIGNER), emptySet(), release))
        assertTrue(updatesInApp(null, setOf(other), emptySet(), null))
        assertFalse(updatesInApp("com.android.vending", setOf(other), emptySet(), null))
    }
}
