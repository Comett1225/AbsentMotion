package app.absentmotion.ui.home

import app.absentmotion.platform.readAtMost
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.adaptive.AdaptiveWindow
import app.absentmotion.ui.adaptive.adaptiveWindowIsWide
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import app.absentmotion.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.export.formatBytes
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.platform.versionName
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

internal data class ApkAsset(val name: String, val url: String, val size: Long, val sha256: String?, val sums: String?, val manifest: String? = null)

internal data class Release(val version: String, val link: String, val apk: ApkAsset? = null) {
    val download: String? get() = apk?.url
}

internal sealed interface UpdateAnswer {
    data class Available(val release: Release, val external: Boolean = false) : UpdateAnswer
    data object Latest : UpdateAnswer
    data object Failed : UpdateAnswer
}

internal object Updates {
    var checking by mutableStateOf(false)
        private set
    var answer by mutableStateOf<UpdateAnswer?>(null)
    var installedOverride: String? = null
    var source: UpdateSource = GitHubSource
    private var checkedAtStart = false
    private var quiet = false
    private val scope = MainScope()

    fun installed(context: Context): String = installedOverride ?: versionName(context)

    fun atStart(context: Context, settings: AppSettings) {
        if (checkedAtStart) return
        checkedAtStart = true
        check(context, settings, manual = false)
    }

    fun hush() {
        quiet = true
        if (answer is UpdateAnswer.Available) answer = null
    }

    fun check(context: Context, settings: AppSettings, manual: Boolean) {
        if (manual && Updater.reveal()) return
        if (manual && checking) return
        if (manual) checking = true
        val current = installed(context)
        val from = source
        val app = context.applicationContext
        scope.launch {
            val (release, inApp) = withContext(Dispatchers.IO) {
                val release = try {
                    fetchLatest(from, quiet = !manual)
                } catch (e: Exception) {
                    Log.i("AMUpdate", "update check failed: $e")
                    null
                }
                release to (release?.apk == null || Updater.inApp(app, from))
            }
            release?.let { settings.rememberNewest(it.version, it.link) }
            val result = offerFor(answerFor(release, current), inApp)
            if (manual) checking = false
            if (manual || (result is UpdateAnswer.Available && !quiet)) answer = result
        }
    }

    private fun fetchLatest(from: UpdateSource, quiet: Boolean): Release? = TrustedHttp(from, TIMEOUT_MS, TIMEOUT_MS, quiet).get(from.releases, mapOf("Accept" to "application/vnd.github+json")) { connection ->
        if (connection.responseCode != HttpURLConnection.HTTP_OK) null
        else parseReleases(connection.inputStream.use { it.readAtMost(MAX_BYTES) }.decodeToString(), source = from)
    }

    private const val TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 4 shl 20
}

internal fun answerFor(release: Release?, current: String): UpdateAnswer {
    val newer = release?.let { isNewer(it.version, current) } ?: return UpdateAnswer.Failed
    return if (newer) UpdateAnswer.Available(release) else UpdateAnswer.Latest
}

internal fun warnMetered(answer: UpdateAnswer.Available, metered: Boolean?) = metered == true && answer.release.apk != null

internal fun offerFor(answer: UpdateAnswer, inApp: Boolean): UpdateAnswer =
    if (answer is UpdateAnswer.Available && answer.release.apk != null && !inApp) UpdateAnswer.Available(answer.release.copy(apk = null), external = true) else answer

internal fun parseRelease(json: String, flavour: String = app.absentmotion.BuildConfig.FLAVOR, source: UpdateSource = GitHubSource, abi: String? = deviceAbi()): Release? =
    runCatching { JSONObject(json) }.getOrNull()?.let { releaseOf(it, flavour, source, abi) }

internal fun parseReleases(json: String, flavour: String = app.absentmotion.BuildConfig.FLAVOR, source: UpdateSource = GitHubSource, abi: String? = deviceAbi()): Release? {
    val list = runCatching { JSONArray(json) }.getOrNull() ?: return null
    return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        .filterNot { it.optBoolean("draft") || it.optBoolean("prerelease") }
        .mapNotNull { releaseOf(it, flavour, source, abi) }
        .maxWithOrNull(compareBy<Release> { Version.parse(it.version) })
}

private fun releaseOf(release: JSONObject, flavour: String, source: UpdateSource, abi: String?): Release? {
    val tag = release.optString("tag_name").trim()
    if (Version.parse(tag) == null) return null
    val page = release.optString("html_url").takeIf { it.startsWith("$HOME_PAGE/", ignoreCase = true) } ?: "$HOME_PAGE/releases/latest"
    val version = tag.removePrefix("v").removePrefix("V")
    return Release(version, page, apkOf(release, tag, version, flavour, source, abi))
}

private val SAFE_VERSION = Regex("^[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z.]+)?$")

internal fun deviceAbi(): String? = Build.SUPPORTED_ABIS.orEmpty().firstOrNull()

internal fun apkName(version: String, flavour: String, abi: String? = null) =
    "AbsentMotion-$version-${when (flavour) { "compat" -> "compatible"; "compat32" -> "compatible-32bit"; else -> flavour }}${if (abi == "x86_64" && flavour != "compat32") "-x86_64" else ""}.apk"

private fun apkOf(release: JSONObject, tag: String, version: String, flavour: String, source: UpdateSource, abi: String?): ApkAsset? {
    if (flavour !in setOf("foss", "compat", "compat32", "desktop") || !SAFE_VERSION.matches(version) || tag.trim() !in setOf("v$version", "V$version")) return null
    val assets = release.optJSONArray("assets") ?: return null
    val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
    fun linkOf(file: String) = list.firstOrNull { it.optString("name") == file }?.takeIf { it.optString("browser_download_url").equals(source.asset(tag.trim(), file), ignoreCase = true) }
    val (name, asset) = (if (flavour == "desktop") installerName(list.map { it.optString("name") }, version)?.let { n -> linkOf(n)?.let { n to it } }
        else apkName(version, flavour, abi).let { n -> linkOf(n)?.let { n to it } }) ?: return null
    val size = asset.optLong("size", -1L).takeIf { it in 1..MAX_APK_BYTES } ?: return null
    val sums = linkOf(SUMS)?.let { source.asset(tag.trim(), SUMS) }
    val manifest = if (flavour == "desktop" && linkOf(MANIFEST) != null && linkOf("$MANIFEST.sig") != null) source.asset(tag.trim(), MANIFEST) else null
    return ApkAsset(name, source.asset(tag.trim(), name), size, parseDigest(asset.optString("digest")), sums, manifest)
}

private const val SUMS = "SHA256SUMS.txt"
private const val MANIFEST = "update-manifest.json"

private val ARM_TAGS = listOf("arm64", "aarch64")
private val INTEL_TAGS = listOf("x64", "x86_64", "amd64", "intel")

internal fun installerExtensions(os: String, site: InstallSite? = installSite()): List<String> = when {
    os.startsWith("mac", ignoreCase = true) -> if (site?.kind == InstallKind.MacApp) listOf("-update.zip", ".dmg") else listOf(".dmg")
    os.startsWith("windows", ignoreCase = true) -> when (site?.kind) {
        InstallKind.Setup -> listOf(".ampack", ".msi")
        InstallKind.Msi -> listOf(".exe", ".msi")
        else -> listOf(".msi", ".exe")
    }
    site?.kind == InstallKind.Deb -> listOf(".deb")
    site?.kind == InstallKind.AppImage -> listOf(".appimage")
    site?.kind == InstallKind.Tarball -> listOf(".tar.gz")
    else -> emptyList()
}

internal fun installerExtension(name: String): String? = listOf(".dmg", ".msi", ".exe", ".ampack", ".deb", ".rpm", ".appimage", ".tar.gz", ".zip").firstOrNull { name.endsWith(it, ignoreCase = true) }

internal fun installerName(names: List<String>, version: String, os: String = System.getProperty("os.name").orEmpty(), arch: String = System.getProperty("os.arch").orEmpty(), site: InstallSite? = installSite()): String? {
    val own = installerExtensions(os, site)
    val wanted = if (arch.contains("aarch64", ignoreCase = true) || arch.contains("arm", ignoreCase = true)) ARM_TAGS else INTEL_TAGS
    val others = (ARM_TAGS + INTEL_TAGS) - wanted.toSet()
    val candidates = names.filter { n -> n.startsWith("absentmotion", ignoreCase = true) && version in n && own.any { n.endsWith(it, ignoreCase = true) } && others.none { n.contains(it, ignoreCase = true) } }
    return own.firstNotNullOfOrNull { ext ->
        val list = candidates.filter { it.endsWith(ext, ignoreCase = true) }
        list.firstOrNull { c -> wanted.any { c.contains(it, ignoreCase = true) } } ?: list.firstOrNull()
    }
}

internal fun isNewer(latest: String, current: String): Boolean? {
    val a = Version.parse(latest) ?: return null
    val b = Version.parse(current) ?: return null
    return a > b
}

internal data class Outdated(val installed: String, val newest: String, val link: String)

internal fun outdatedHint(allowed: Boolean, installed: String, newest: String?, link: String?): Outdated? {
    if (!allowed || newest == null || link == null || !link.startsWith("$HOME_PAGE/", ignoreCase = true)) return null
    return if (isNewer(newest, installed) == true) Outdated(installed, newest, link) else null
}

@Composable
internal fun outdatedNow(settings: AppSettings): Outdated? {
    val context = LocalContext.current
    return outdatedHint(settings.autoUpdateCheck, Updates.installed(context), settings.newestVersion, settings.newestLink)
}

@Composable
internal fun OutdatedHint(outdated: Outdated?, modifier: Modifier = Modifier) {
    outdated ?: return
    val links = LocalUriHandler.current
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.update_outdated, outdated.installed, outdated.newest), color = Am.Navy)
        DialogPill(stringResource(R.string.update_download), Modifier.align(Alignment.End).padding(top = 8.dp), primary = false) { runCatching { links.openUri(outdated.link) } }
    }
}

internal class Version private constructor(private val numbers: List<Int>, private val pre: List<String>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val d = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
        for ((a, b) in pre.zip(other.pre)) {
            val x = a.toIntOrNull()
            val y = b.toIntOrNull()
            val d = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
            if (d != 0) return d
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        fun parse(text: String): Version? {
            val plain = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val numbers = plain.substringBefore('-').split('.').map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
            return Version(numbers, plain.substringAfter('-', "").split('.').filter { it.isNotEmpty() })
        }
    }
}

@Composable
internal fun UpdateAskDialog(settings: AppSettings) {
    if (adaptiveWindowIsWide()) {
        Dialog({}, sheetDialogProperties()) {
            AdaptiveWindow(900.dp, 300.dp) {
                Column(Modifier.fillMaxSize().background(Am.Card).padding(24.dp)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(stringResource(R.string.update_check_on_open), Modifier.weight(1f).verticalScroll(rememberScrollState()), color = Am.Navy, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.update_check_note), Modifier.weight(1f).verticalScroll(rememberScrollState()), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    Row(Modifier.align(Alignment.End).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogPill(stringResource(R.string.update_not_now), primary = false) { settings.chooseUpdates(false) }
                        DialogPill(stringResource(R.string.update_turn_on)) { settings.chooseUpdates(true) }
                    }
                }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = {},
        text = {
            Column {
                Text(stringResource(R.string.update_check_on_open), color = Am.Navy, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.update_check_note), Modifier.padding(top = 6.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)
            }
        },
        dismissButton = { DialogPill(stringResource(R.string.update_not_now), primary = false) { settings.chooseUpdates(false) } },
        confirmButton = { DialogPill(stringResource(R.string.update_turn_on)) { settings.chooseUpdates(true) } },
        containerColor = Am.Card,
    )
}

@Composable
internal fun WideUpdateCard(icon: ImageVector, title: String, version: String?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().background(Am.Card).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(0.4f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Am.Navy.copy(alpha = 0.08f)), Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = Am.Navy)
                }
                if (version != null) Text(version, Modifier.padding(start = 12.dp).clip(RoundedCornerShape(10.dp)).background(Am.Navy.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp), color = Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Text(title, Modifier.padding(top = 16.dp).semantics { heading() }, color = Am.Navy, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(0.6f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
internal fun UpdateOfferContent(available: UpdateAnswer.Available, installed: String, metered: Boolean, sourceLabel: String?, onChangelog: () -> Unit, onLater: () -> Unit, onDownload: () -> Unit) {
    val release = available.release
    @Composable fun detail(text: String) {
        Text(text, Modifier.padding(top = 6.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)
    }
    detail(stringResource(R.string.update_installed, installed))
    release.apk?.let { detail(stringResource(R.string.update_size, formatBytes(it.size))) }
    if (available.external) detail(stringResource(R.string.update_external))
    if (metered) detail(stringResource(R.string.update_metered))
    sourceLabel?.let { detail(it) }
    FlowRow(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DialogPill(stringResource(R.string.update_changelog), primary = false, onClick = onChangelog)
        DialogPill(stringResource(R.string.update_later), primary = false, onClick = onLater)
        if (release.apk != null || !available.external) Button(onDownload, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Am.Navy, contentColor = Am.OnNavy)) {
            Text(stringResource(if (release.apk != null) R.string.update_download_update else R.string.update_download), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
