package app.absentmotion.ui.home

import app.absentmotion.platform.readAtMost
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import app.absentmotion.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

internal data class ApkAsset(val name: String, val url: String, val size: Long, val sha256: String?, val sums: String?)

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
                    fetchLatest(from)
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

    private fun fetchLatest(from: UpdateSource): Release? = TrustedHttp(from, TIMEOUT_MS, TIMEOUT_MS).get(from.releases, mapOf("Accept" to "application/vnd.github+json")) { connection ->
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

internal fun parseRelease(json: String, flavour: String = app.absentmotion.BuildConfig.FLAVOR, source: UpdateSource = GitHubSource): Release? =
    runCatching { JSONObject(json) }.getOrNull()?.let { releaseOf(it, flavour, source) }

internal fun parseReleases(json: String, flavour: String = app.absentmotion.BuildConfig.FLAVOR, source: UpdateSource = GitHubSource): Release? {
    val list = runCatching { JSONArray(json) }.getOrNull() ?: return null
    return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        .filterNot { it.optBoolean("draft") || it.optBoolean("prerelease") }
        .mapNotNull { releaseOf(it, flavour, source) }
        .maxWithOrNull(compareBy<Release> { Version.parse(it.version) })
}

private fun releaseOf(release: JSONObject, flavour: String, source: UpdateSource): Release? {
    val tag = release.optString("tag_name").trim()
    if (Version.parse(tag) == null) return null
    val page = release.optString("html_url").takeIf { it.startsWith("$HOME_PAGE/", ignoreCase = true) } ?: "$HOME_PAGE/releases/latest"
    val version = tag.removePrefix("v").removePrefix("V")
    return Release(version, page, apkOf(release, tag, version, flavour, source))
}

private val SAFE_VERSION = Regex("^[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z.]+)?$")

internal fun apkName(version: String, flavour: String) = "AbsentMotion-$version-${if (flavour == "compat") "compatible" else flavour}.apk"

private fun apkOf(release: JSONObject, tag: String, version: String, flavour: String, source: UpdateSource): ApkAsset? {
    if (flavour !in setOf("foss", "play", "compat") || !SAFE_VERSION.matches(version) || tag.trim() !in setOf("v$version", "V$version")) return null
    val name = apkName(version, flavour)
    val assets = release.optJSONArray("assets") ?: return null
    val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
    fun linkOf(file: String) = list.firstOrNull { it.optString("name") == file }?.takeIf { it.optString("browser_download_url").equals(source.asset(tag.trim(), file), ignoreCase = true) }
    val asset = linkOf(name) ?: return null
    val size = asset.optLong("size", -1L).takeIf { it in 1..MAX_APK_BYTES } ?: return null
    val sums = linkOf(SUMS)?.let { source.asset(tag.trim(), SUMS) }
    return ApkAsset(name, source.asset(tag.trim(), name), size, parseDigest(asset.optString("digest")), sums)
}

private const val SUMS = "SHA256SUMS.txt"

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
        TextButton({ runCatching { links.openUri(outdated.link) } }, Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.update_download), color = Am.Navy)
        }
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
    AlertDialog(
        onDismissRequest = {},
        text = {
            Column {
                Text(stringResource(R.string.update_check_on_open), color = Am.Navy, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.update_check_note), Modifier.padding(top = 6.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)
            }
        },
        dismissButton = { TextButton({ settings.chooseUpdates(false) }) { Text(stringResource(R.string.update_not_now), color = Am.Navy) } },
        confirmButton = { TextButton({ settings.chooseUpdates(true) }) { Text(stringResource(R.string.update_turn_on), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
