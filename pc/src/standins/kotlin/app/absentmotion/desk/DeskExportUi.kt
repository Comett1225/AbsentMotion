package app.absentmotion.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.export.DeskExports
import app.absentmotion.export.ExportRun
import app.absentmotion.ui.home.DeskButton
import app.absentmotion.ui.theme.Am

@Composable
internal fun DeskQuitAsk(app: DeskAppState) {
    DeskAsk(stringResource(R.string.desk_quit_title), stringResource(R.string.desk_quit_body), 520.dp, onClose = { app.quitAsk = false }, onEnter = app::keepExporting) {
        DeskButton(stringResource(R.string.cancel), { app.quitAsk = false })
        DeskButton(stringResource(R.string.desk_quit_stop), app::stopAndQuit, danger = true)
        DeskButton(stringResource(R.string.desk_quit_keep), app::keepExporting, primary = true, minWidth = 120.dp)
    }
}

@Composable
internal fun DeskExportPill(modifier: Modifier) {
    if (!DeskExports.running) return
    val percent = (DeskExports.fraction * 100).toInt().coerceIn(0, 100)
    Row(
        modifier.shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Am.Field).border(1.dp, Am.Line, RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.desk_export_progress, stringResource(DeskExports.title), percent), color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
        Box(Modifier.padding(start = 12.dp).width(96.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Am.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(Am.Accent))
        }
        Spacer(Modifier.width(8.dp))
        DeskButton(stringResource(R.string.cancel), { ExportRun.cancel() }, Modifier.padding(vertical = 4.dp), height = 28.dp)
    }
}

@Composable
internal fun DeskExportMini(app: DeskAppState) {
    val finish = DeskExports.finish
    val running = DeskExports.running
    val percent = (DeskExports.fraction * 100).toInt().coerceIn(0, 100)
    LaunchedEffect(running) { if (!running) DeskTaskbar.attention() }
    Column(Modifier.fillMaxSize().background(Am.HomeBg).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(
            when {
                running -> stringResource(DeskExports.title)
                finish?.ok == true -> stringResource(R.string.export_notify_done)
                else -> stringResource(R.string.export_notify_failed)
            },
            color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        if (running) {
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Am.HomeLine)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(Am.Accent))
            }
            Text("$percent%", Modifier.padding(top = 8.dp), color = Am.HomeMid, fontSize = 13.sp)
        } else if (finish?.where?.isNotBlank() == true) {
            Text(finish.where, Modifier.padding(top = 10.dp), color = Am.HomeMid, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (running) {
                DeskButton(stringResource(R.string.cancel), { ExportRun.cancel() })
                DeskButton(stringResource(R.string.desk_export_open_app), { app.hidden = false }, primary = true)
            } else {
                if (finish?.files?.isNotEmpty() == true) DeskButton(stringResource(R.string.desk_export_show_file), { DeskFiles.reveal(finish.files) })
                DeskButton(stringResource(R.string.desk_export_open_app), { app.hidden = false })
                DeskButton(stringResource(R.string.desk_export_quit), { app.requestQuit() }, primary = true)
            }
        }
    }
}
