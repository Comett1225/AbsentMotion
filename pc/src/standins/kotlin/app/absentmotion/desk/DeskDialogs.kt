package app.absentmotion.desk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.fonts.FontName
import app.absentmotion.ui.home.DeskButton
import app.absentmotion.ui.home.DeskModal
import app.absentmotion.ui.theme.Am

@Composable
internal fun DeskAsk(title: String?, body: String, width: androidx.compose.ui.unit.Dp = 480.dp, onClose: () -> Unit, onEnter: (() -> Unit)? = null, actions: @Composable () -> Unit) {
    DeskModal(onClose, width, onEnter = onEnter, dismissOnScrim = false) {
        if (title != null) Text(title, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(body, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (title != null) 12.dp else 24.dp, bottom = 8.dp), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)
        Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) { actions() }
    }
}

@Composable
internal fun DeskSaveFailed(app: DeskAppState) {
    DeskAsk(null, stringResource(R.string.desk_close_save_failed), onClose = { app.saveFailed = false; app.afterClose = null }, onEnter = app::retryClose) {
        DeskButton(stringResource(R.string.home_save_failed_leave), app::closeAnyway)
        DeskButton(stringResource(R.string.home_save_failed_retry), app::retryClose, primary = true)
    }
}

@Composable
internal fun DeskFontOffer(names: List<String>, download: () -> Unit, decline: () -> Unit) {
    DeskAsk(stringResource(R.string.desk_fonts_missing_title), stringResource(R.string.desk_fonts_missing_body, names.joinToString(", ") { FontName.parse(it).label }), onClose = decline, onEnter = download) {
        DeskButton(stringResource(R.string.update_not_now), decline)
        DeskButton(stringResource(R.string.update_download), download, primary = true, minWidth = 92.dp)
    }
}
