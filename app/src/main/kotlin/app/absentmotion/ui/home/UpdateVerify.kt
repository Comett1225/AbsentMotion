package app.absentmotion.ui.home

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import app.absentmotion.platform.Context
import java.io.File
import java.security.MessageDigest

internal const val VARIANT_KEY = "app.absentmotion.variant"

internal data class ApkFacts(
    val packageName: String, val versionCode: Long, val versionName: String?, val variant: String?,
    val signers: Set<String>, val history: Set<String> = emptySet(),
)

internal data class InstalledApp(val packageName: String, val versionCode: Long, val versionName: String, val variant: String, val signers: Set<String>)

internal fun signersMatch(installed: Set<String>, current: Set<String>, history: Set<String>): Boolean {
    if (installed.isEmpty() || current.isEmpty()) return false
    return current == installed || (installed.size == 1 && current.size == 1 && installed.first() in history)
}

internal fun judgeApk(apk: ApkFacts?, installed: InstalledApp, version: String): UpdateFailure? {
    apk ?: return UpdateFailure.WrongApp
    if (apk.packageName != installed.packageName) return UpdateFailure.WrongApp
    if (!signersMatch(installed.signers, apk.signers, apk.history)) return UpdateFailure.Signature
    if (apk.variant != installed.variant) return UpdateFailure.WrongApp
    if (apk.versionName != version || apk.versionCode <= installed.versionCode || isNewer(version, installed.versionName) != true) return UpdateFailure.NotNewer
    return null
}

internal fun installedApp(context: Context, versionName: String): InstalledApp? = runCatching {
    val info = packageInfo(context.packageManager, context.packageName)
    InstalledApp(context.packageName, info.longVersionCode, versionName, app.absentmotion.BuildConfig.FLAVOR, signers(info).first)
}.getOrNull()

internal fun installedSigning(context: Context): Pair<Set<String>, Set<String>>? = runCatching {
    signers(packageInfo(context.packageManager, context.packageName))
}.getOrNull()

internal fun installerOf(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    else legacyInstaller(context.packageManager, context.packageName)
}.getOrNull()

@Suppress("DEPRECATION")
private fun legacyInstaller(manager: PackageManager, name: String): String? = manager.getInstallerPackageName(name)

internal fun apkFacts(context: Context, file: File): ApkFacts? = runCatching {
    val info = archiveInfo(context.packageManager, file.path) ?: return null
    val (current, history) = signers(info)
    ApkFacts(info.packageName, info.longVersionCode, info.versionName, info.applicationInfo?.metaData?.getString(VARIANT_KEY), current, history)
}.getOrNull()

private fun signers(info: PackageInfo): Pair<Set<String>, Set<String>> {
    val signing = info.signingInfo
    if (signing != null) {
        val current = signing.apkContentsSigners.orEmpty().map(::fingerprint).toSet()
        val history = if (signing.hasMultipleSigners()) emptySet() else signing.signingCertificateHistory.orEmpty().map(::fingerprint).toSet()
        if (current.isNotEmpty()) return current to history
    }
    return legacySignatures(info).map(::fingerprint).toSet() to emptySet()
}

@Suppress("DEPRECATION")
private fun legacySignatures(info: PackageInfo): List<Signature> = info.signatures.orEmpty().filterNotNull()

private fun fingerprint(signature: Signature): String = hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))

private fun packageInfo(manager: PackageManager, name: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) manager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    else legacyPackageInfo(manager, name)

@Suppress("DEPRECATION")
private fun legacyPackageInfo(manager: PackageManager, name: String): PackageInfo =
    manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)

private fun archiveInfo(manager: PackageManager, path: String): PackageInfo? =
    if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of((PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA).toLong()))
    } else legacyArchiveInfo(manager, path)

@Suppress("DEPRECATION")
private fun legacyArchiveInfo(manager: PackageManager, path: String): PackageInfo? =
    manager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA)
