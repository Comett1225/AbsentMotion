package app.absentmotion.ui.home

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am

@Composable
internal fun UpdateDialog() {
    val context = LocalContext.current
    val step = Updater.step
    val prompt = Updater.confirm
    LaunchedEffect(prompt) {
        prompt ?: return@LaunchedEffect
        val activity = context as? Activity
        val failed = activity == null || runCatching { activity.startActivity(prompt) }.isFailure
        Updater.confirmShown(context, failed)
    }
    if (step != UpdateStep.Idle) {
        if (!Updater.hidden) UpdateFlowDialog(step)
        return
    }
    val shown = Updates.answer ?: return
    val close = { Updates.answer = null }
    val available = shown as? UpdateAnswer.Available
    if (available == null) {
        UpdateCard(app.absentmotion.ui.editor.PanelIcons.Check, stringResource(if (shown == UpdateAnswer.Latest) R.string.update_latest else R.string.update_failed), close) {
            Buttons { Primary(stringResource(R.string.ok), close) }
        }
        return
    }
    val release = available.release
    val links = LocalUriHandler.current
    fun open(link: String) = runCatching { links.openUri(link) }.onFailure {
        android.widget.Toast.makeText(context, context.getString(R.string.update_open_failed), android.widget.Toast.LENGTH_LONG).show()
    }
    UpdateCard(HomeIcons.ArrowDown, stringResource(R.string.update_available, release.version), close) {
        Detail(stringResource(R.string.update_installed, Updates.installed(context)))
        release.apk?.let { Detail(stringResource(R.string.update_size, formatBytes(it.size))) }
        if (available.external) Detail(stringResource(R.string.update_external))
        if (warnMetered(available, remember(available) { Updater.metered(context) })) Detail(stringResource(R.string.update_metered))
        Updates.source.label?.let { Detail(it) }
        Buttons {
            Secondary(stringResource(R.string.update_changelog)) { open(release.link) }
            Secondary(stringResource(R.string.update_later), close)
            if (release.apk != null) Primary(stringResource(R.string.update_download_update)) { close(); Updater.start(context, release) }
            else if (!available.external) Primary(stringResource(R.string.update_download)) { close(); open(release.link) }
        }
    }
}

@Composable
private fun UpdateFlowDialog(step: UpdateStep) {
    val context = LocalContext.current
    val release = step.release ?: return
    val hide = { Updater.hidden = true }
    val cancel = { Updater.cancel(context) }
    when (step) {
        is UpdateStep.Downloading -> UpdateCard(HomeIcons.ArrowDown, stringResource(R.string.update_downloading), hide, release.version) {
            val fraction = if (step.total > 0) step.done.toFloat() / step.total else 0f
            LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(4.dp)), color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(R.string.update_progress, formatBytes(step.done), formatBytes(step.total)), Modifier.weight(1f), color = Am.HomeMid, fontSize = 14.sp)
                Text("${(fraction * 100).toInt()}%", color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (step.waiting) Body(stringResource(R.string.update_reconnecting), live = true)
            Buttons { Secondary(stringResource(R.string.cancel), cancel) }
        }
        is UpdateStep.Checking -> UpdateCard(app.absentmotion.ui.export.ExportIcons.Lock, stringResource(R.string.update_checking_download), {}, release.version) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(4.dp)), color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f))
            Buttons { Secondary(stringResource(R.string.cancel), cancel) }
        }
        is UpdateStep.Permission -> UpdateCard(app.absentmotion.ui.export.ExportIcons.Lock, stringResource(R.string.update_permission_title), cancel, release.version) {
            Body(stringResource(when { step.denied -> R.string.update_permission_denied; step.downloaded -> R.string.update_permission_body; else -> R.string.update_permission_first }), live = step.denied)
            Buttons {
                Secondary(stringResource(R.string.cancel), cancel)
                Primary(stringResource(R.string.camera_open_settings)) { (context as? Activity)?.let(Updater::openSettings) }
            }
        }
        is UpdateStep.Installing -> UpdateCard(HomeIcons.ArrowDown, stringResource(R.string.update_waiting), {}, release.version) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(4.dp)), color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f))
            Buttons { Secondary(stringResource(R.string.cancel), cancel) }
        }
        is UpdateStep.NotInstalled -> UpdateCard(HomeIcons.ArrowDown, stringResource(R.string.update_available, release.version), cancel) {
            Body(stringResource(R.string.update_not_installed), live = true)
            Buttons {
                Secondary(stringResource(R.string.update_later), cancel)
                Primary(stringResource(R.string.update_install)) { Updater.install(context) }
            }
        }
        is UpdateStep.Failed -> UpdateCard(HomeIcons.ArrowDown, stringResource(R.string.update_stopped), cancel, release.version) {
            val failure = step.failure
            val text = when (failure) {
                is UpdateFailure.Server -> stringResource(failure.message, failure.code.toString())
                is UpdateFailure.Storage -> stringResource(failure.message, formatBytes(failure.needed))
                else -> stringResource(failure.message)
            }
            Body(text, live = true)
            Buttons {
                Secondary(stringResource(R.string.update_later), cancel)
                if (failure.retry) Primary(stringResource(R.string.home_save_failed_retry)) { Updater.start(context, release) }
            }
        }
        UpdateStep.Idle -> Unit
    }
}

@Composable
private fun UpdateCard(icon: ImageVector, title: String, onDismiss: () -> Unit, version: String? = null, content: @Composable () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Am.Card, shadowElevation = 6.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Am.Navy.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(22.dp), tint = Am.Navy)
                        }
                        if (version != null) {
                            Spacer(Modifier.width(12.dp))
                            Text(version, Modifier.clip(RoundedCornerShape(10.dp)).background(Am.Navy.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp),
                                color = Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                    Text(title, Modifier.padding(top = 16.dp).semantics { heading() }, color = Am.Navy, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    content()
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String) = Text(text, Modifier.padding(top = 6.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
private fun Body(text: String, live: Boolean) =
    Text(text, Modifier.padding(top = 10.dp).then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 22.sp)

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp)) { Text(label, color = Am.Navy, fontSize = 15.sp) }
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    Button(onClick, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Am.Navy, contentColor = Am.OnNavy)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
