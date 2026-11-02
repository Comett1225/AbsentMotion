package app.absentmotion.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am

internal object Updater {
    fun inApp(context: Context, source: UpdateSource): Boolean = false
    fun reveal(): Boolean = false
}

@Composable
internal fun UpdateDialog() {
    val shown = Updates.answer ?: return
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val close = { Updates.answer = null }
    val available = shown as? UpdateAnswer.Available
    val release = available?.release
    DeskModal(close, 480.dp, onEnter = { if (release != null) { close(); runCatching { links.openUri(release.link) } } else close() }) {
        DeskDialogHeader(
            if (release != null) stringResource(R.string.update_available, release.version) else stringResource(if (shown == UpdateAnswer.Latest) R.string.update_latest else R.string.update_failed), close,
        )
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (release != null) {
                Text(stringResource(R.string.update_installed, Updates.installed(context)), color = Am.HomeInk, fontSize = 14.sp)
                if (available.external) Text(stringResource(R.string.update_external), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp)
                Updates.source.label?.let { Text(it, color = Am.HomeMid, fontSize = 13.sp) }
            } else Text(stringResource(R.string.update_installed, Updates.installed(context)), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Normal)
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (release != null) {
                DeskButton(stringResource(R.string.update_changelog), { runCatching { links.openUri(release.link) } })
                DeskButton(stringResource(R.string.update_later), close)
                DeskButton(stringResource(R.string.update_download), { close(); runCatching { links.openUri(release.link) } }, primary = true)
            } else DeskButton(stringResource(R.string.ok), close, primary = true, minWidth = 96.dp)
        }
    }
}
