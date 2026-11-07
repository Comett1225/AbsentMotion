package app.absentmotion.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.library.CrashEntry
import app.absentmotion.library.CrashLogs
import app.absentmotion.library.redactPaths
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.Log
import app.absentmotion.platform.readAtMost
import app.absentmotion.platform.versionName
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal data class ReportLinks(val issues: String, val discord: String, val form: String, val feature: String = BuiltInFeature) {
    fun named(name: String): String? = when (name) {
        "github-issues" -> issues
        "discord" -> discord
        "bug-form" -> form
        "feature-form" -> feature
        else -> name.takeIf { it.startsWith("https://") }
    }

    companion object {
        val BuiltIn = ReportLinks("$HOME_PAGE/issues", "https://discord.gg/xXEHj8Sx8X", "https://forms.gle/6bY8KBPMKc2iUypV6", BuiltInFeature)
        private const val PREFS = "report_links"
        @Volatile private var fetched: ReportLinks? = null

        fun stored(context: Context): ReportLinks {
            fetched?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BuiltIn.copy(
                discord = prefs.getString("discord", null)?.takeIf(::isDiscordLink) ?: BuiltIn.discord,
                form = prefs.getString("form", null)?.takeIf(::isFormLink) ?: BuiltIn.form,
                feature = prefs.getString("feature", null)?.takeIf(::isFormLink) ?: BuiltIn.feature,
            )
        }

        fun refresh(context: Context): ReportLinks {
            fetched?.let { return it }
            val now = stored(context)
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!settings.getBoolean("checkUpdates", true) || !settings.getBoolean("updatesAsked", false)) return now
            val readme = try {
                TrustedHttp(ReadmeSource, 5_000, 5_000).get(ReadmeSource.releases, mapOf("Accept" to "application/vnd.github.raw")) { connection ->
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) null else connection.inputStream.use { it.readAtMost(1 shl 20) }.decodeToString()
                }
            } catch (e: Exception) {
                Log.i("AMReport", "readme fetch failed: $e")
                null
            } ?: return now
            val found = parseReportLinks(readme, now)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("discord", found.discord).putString("form", found.form).putString("feature", found.feature).apply()
            fetched = found
            return found
        }
    }
}

private const val BuiltInFeature = "https://forms.gle/5ZohVpB2eBPvzhu4A"

@Composable
internal fun rememberReportLinks(): ReportLinks {
    val context = LocalContext.current
    var found by remember { mutableStateOf(ReportLinks.stored(context)) }
    LaunchedEffect(Unit) { found = withContext(Dispatchers.IO) { ReportLinks.refresh(context) } }
    return found
}

internal object ReadmeSource : UpdateSource {
    private val repository = HOME_PAGE.removePrefix("https://github.com/").lowercase(Locale.ROOT)
    override val releases = "https://api.github.com/repos/" + HOME_PAGE.removePrefix("https://github.com/") + "/readme"
    override fun asset(tag: String, name: String) = releases

    override fun allows(url: URI, first: Boolean): Boolean {
        if (!url.isAbsolute || url.isOpaque || !url.scheme.equals("https", ignoreCase = true)) return false
        if (url.rawUserInfo != null || url.rawFragment != null || (url.port != -1 && url.port != 443)) return false
        if (!url.host.equals("api.github.com", ignoreCase = true)) return false
        val path = url.rawPath.orEmpty().lowercase(Locale.ROOT)
        return path == "/repos/$repository/readme" || (!first && path.matches(Regex("/repositories/[0-9]+/readme")))
    }
}

private val DiscordLink = Regex("""https://(?:discord\.gg|discord\.com/invite)/[A-Za-z0-9_-]+""")
private val FormLink = Regex("""https://(?:forms\.gle/[A-Za-z0-9_-]+|docs\.google\.com/forms/d/(?:e/)?[A-Za-z0-9_-]+/viewform)""")
private val BugLine = Regex("""(?i)report[\s%20+-]*a[\s%20+-]*bug|bug report""")
private val FeatureLine = Regex("""(?i)suggest[\s%20+-]*a[\s%20+-]*feature|feature request""")

internal fun isDiscordLink(text: String) = DiscordLink.matches(text)

internal fun isFormLink(text: String) = FormLink.matches(text)

internal fun parseReportLinks(readme: String, fallback: ReportLinks = ReportLinks.BuiltIn): ReportLinks {
    val discord = DiscordLink.find(readme)?.value
    val form = readme.lineSequence().filter { BugLine.containsMatchIn(it) && !it.contains("feature", ignoreCase = true) }.firstNotNullOfOrNull { FormLink.find(it)?.value }
    val feature = readme.lineSequence().filter { FeatureLine.containsMatchIn(it) && !BugLine.containsMatchIn(it) }.firstNotNullOfOrNull { FormLink.find(it)?.value }
    return fallback.copy(discord = discord ?: fallback.discord, form = form ?: fallback.form, feature = feature ?: fallback.feature)
}

private val HomeFolders = listOf(Regex("""(?i)(?:\b[a-z]:)?[\\/]+(?:users|home)[\\/]+[^\\/\s"'<>]+"""))
private val MailAddress = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

internal fun scrubReport(text: String, home: String? = System.getProperty("user.home")): String {
    var out = text
    if (!home.isNullOrBlank() && home.length > 1) out = out.replace(home, "~").replace(home.replace('\\', '/'), "~")
    out = HomeFolders.fold(out) { acc, regex -> regex.replace(acc, "~") }
    return MailAddress.replace(redactPaths(out), "[email]")
}

internal fun reportDetails(context: Context, summary: String): String {
    val system = if (PlatformFeatures.desktop) "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        else "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
    return scrubReport("AbsentMotion ${versionName(context)}\n$system\n\n$summary")
}

@Suppress("DEPRECATION")
@Composable
internal fun ReportDialog(summary: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val opener = LocalUriHandler.current
    val details = remember(summary) { reportDetails(context, summary) }
    val found = rememberReportLinks()
    LaunchedEffect(details) { clipboard.setText(AnnotatedString(details)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title), color = Am.Navy) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_copied), color = Am.Navy, fontSize = 14.sp, lineHeight = 19.sp)
                ReportLinkButton(stringResource(R.string.report_github)) { runCatching { opener.openUri(found.issues) } }
                ReportLinkButton(stringResource(R.string.report_discord)) { runCatching { opener.openUri(found.discord) } }
                ReportLinkButton(stringResource(R.string.report_form)) { runCatching { opener.openUri(found.form) } }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
private fun ReportLinkButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp)).background(Am.Navy).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Am.OnNavy, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

@Composable
internal fun CrashReportDialog(log: CrashEntry, onDismiss: () -> Unit) {
    val sentence = stringResource(log.reason.sentence)
    val detail by produceState<String?>(null, log.file) { value = withContext(Dispatchers.IO) { CrashLogs.text(log) } }
    detail?.let { ReportDialog("$sentence\n\n$it", onDismiss) }
}
