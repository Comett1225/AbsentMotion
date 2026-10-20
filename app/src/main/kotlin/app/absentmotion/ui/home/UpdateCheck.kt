package app.absentmotion.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

internal data class Release(val version: String, val link: String)

internal sealed interface UpdateAnswer {
    data class Available(val release: Release) : UpdateAnswer
    data object Latest : UpdateAnswer
    data object Failed : UpdateAnswer
}

internal object Updates {
    var checking by mutableStateOf(false)
        private set
    var answer by mutableStateOf<UpdateAnswer?>(null)
    private var checkedAtStart = false
    private val scope = MainScope()

    fun atStart(context: Context) {
        if (checkedAtStart) return
        checkedAtStart = true
        check(context, manual = false)
    }

    fun check(context: Context, manual: Boolean) {
        if (manual && checking) return
        if (manual) checking = true
        val current = versionName(context)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    answerFor(fetchLatest(), current)
                } catch (e: Exception) {
                    Log.i("AMUpdate", "update check failed: $e")
                    UpdateAnswer.Failed
                }
            }
            if (manual) checking = false
            if (manual || result is UpdateAnswer.Available) answer = result
        }
    }

    private fun fetchLatest(): Release? {
        val connection = URI(LATEST_RELEASE).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "AbsentMotion")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return parseRelease(connection.inputStream.use { it.readNBytes(MAX_BYTES) }.decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    private val LATEST_RELEASE = "https://api.github.com/repos/" + HOME_PAGE.removePrefix("https://github.com/") + "/releases/latest"
    private const val TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 1 shl 20
}

internal fun answerFor(release: Release?, current: String): UpdateAnswer {
    val newer = release?.let { isNewer(it.version, current) } ?: return UpdateAnswer.Failed
    return if (newer) UpdateAnswer.Available(release) else UpdateAnswer.Latest
}

internal fun parseRelease(json: String): Release? {
    val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val tag = release.optString("tag_name").trim()
    if (Version.parse(tag) == null) return null
    val page = release.optString("html_url").takeIf { it.startsWith("$HOME_PAGE/", ignoreCase = true) } ?: "$HOME_PAGE/releases/latest"
    return Release(tag.removePrefix("v").removePrefix("V"), page)
}

internal fun isNewer(latest: String, current: String): Boolean? {
    val a = Version.parse(latest) ?: return null
    val b = Version.parse(current) ?: return null
    return a > b
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

@Composable
internal fun UpdateDialog() {
    val shown = Updates.answer ?: return
    val links = LocalUriHandler.current
    val close = { Updates.answer = null }
    val available = shown as? UpdateAnswer.Available
    AlertDialog(
        onDismissRequest = close,
        title = if (available == null) null else {
            { Text(stringResource(R.string.update_available, available.release.version), color = Am.Navy) }
        },
        text = if (available != null) null else {
            { Text(stringResource(if (shown == UpdateAnswer.Latest) R.string.update_latest else R.string.update_failed), color = Am.Navy) }
        },
        dismissButton = if (available == null) null else {
            { TextButton(close) { Text(stringResource(R.string.update_later), color = Am.Navy) } }
        },
        confirmButton = {
            if (available == null) TextButton(close) { Text(stringResource(R.string.ok), color = Am.Navy) }
            else TextButton({ close(); runCatching { links.openUri(available.release.link) } }) { Text(stringResource(R.string.update_download), color = Am.Navy) }
        },
        containerColor = Am.Card,
    )
}
