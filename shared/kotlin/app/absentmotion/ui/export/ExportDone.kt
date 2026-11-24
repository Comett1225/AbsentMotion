package app.absentmotion.ui.export
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportResult
import app.absentmotion.export.verificationLine
import app.absentmotion.i18n.text
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.presence
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
fun DoneScreen(kind: ExportKind, result: ExportResult, onClose: () -> Unit, staged: Boolean = false, saving: Boolean = false, saved: Boolean = false, onSave: () -> Unit = {}, onShare: () -> Unit) {
    app.absentmotion.platform.ExportBars(dark = true, running = false)
    Column(Modifier.fillMaxSize().presence().enterFade(10.dp).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar)).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (staged && !saved) R.string.export_ready else R.string.saved_to_storage), color = Am.OnDone, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(if (staged && !saved) "" else result.where, Modifier.heightIn(min = 18.dp), color = Am.OnDone.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            result.verification?.takeUnless { it.ok }?.let { v ->
                Text(
                    verificationLine(v, still = kind == ExportKind.Frame || kind == ExportKind.FrameAs).text(), Modifier.padding(top = 2.dp),
                    color = Am.OnDoneAccent,
                    fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3,
                )
            }
            result.verification?.takeIf { it.ok && it.unchecked }?.let {
                Text(
                    stringResource(R.string.export_checked_unavailable), Modifier.padding(top = 2.dp),
                    color = Am.OnDone.copy(alpha = 0.7f),
                    fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Am.Scrim)) {
            app.absentmotion.platform.ExportMediaPreview(kind, result.uris)
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            val pills = !app.absentmotion.platform.PlatformFeatures.desktop && app.absentmotion.ui.editor.isTablet(androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp)
            if (staged) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BottomButton(stringResource(if (saving) R.string.export_saving else saveLabel(kind)), if (saved) app.absentmotion.ui.editor.PanelIcons.Check else app.absentmotion.ui.home.HomeIcons.Download,
                    pills, primary = true, enabled = !saving && !saved, dim = saving, modifier = Modifier.weight(1f), onClick = onSave)
                BottomButton(stringResource(shareLabel(kind)), ExportIcons.Share, pills, primary = false, modifier = Modifier.weight(1f), onClick = onShare)
            } else BottomButton(stringResource(shareLabel(kind)), ExportIcons.Share, pills, primary = true, onClick = onShare)
            BottomButton(stringResource(R.string.ok), null, pills, primary = false, onClick = onClose)
        }
    }
}

@StringRes
private fun saveLabel(kind: ExportKind) = when (kind) {
    ExportKind.Gif -> R.string.export_save_gif
    ExportKind.Frame, ExportKind.FrameAs -> R.string.export_save_image
    else -> R.string.export_save_video
}

@StringRes
internal fun shareLabel(kind: ExportKind) = when (kind) {
    ExportKind.Video -> R.string.export_share_video
    ExportKind.Gif -> R.string.export_share_gif
    ExportKind.Sequence -> R.string.export_share_sequence
    ExportKind.Frame, ExportKind.FrameAs -> R.string.export_share_image
    ExportKind.Xml -> R.string.export_share_xml
    ExportKind.Package -> R.string.export_share
    ExportKind.Audio -> R.string.export_share_audio
}

@Composable
private fun BottomButton(label: String, icon: ImageVector?, pill: Boolean, primary: Boolean, enabled: Boolean = true, dim: Boolean = !enabled, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val content = if (pill && primary) Am.OnAccent else Am.OnDone
    Row(
        (if (!pill) modifier.fillMaxWidth().height(57.dp).clip(RoundedCornerShape(5.dp)).background(Am.DoneButton)
        else modifier.fillMaxWidth().height(48.dp).clip(androidx.compose.foundation.shape.CircleShape).then(if (primary) Modifier.background(Am.Accent) else Modifier.border(1.dp, Am.OnDone.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape)))
            .alpha(if (dim) 0.6f else 1f).clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(start = 12.dp, end = 10.dp).size(20.dp), tint = content)
        Text(label, Modifier.padding(end = if (icon != null) 12.dp else 0.dp), color = content, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(11.sp, if (pill) 16.sp else 17.sp, 0.5.sp))
    }
}

