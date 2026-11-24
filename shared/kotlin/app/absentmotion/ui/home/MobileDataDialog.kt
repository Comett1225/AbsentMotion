package app.absentmotion.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.platform.MobileData
import app.absentmotion.ui.theme.Am

@Composable
fun MobileDataDialog() {
    if (MobileData.question == null) return
    AlertDialog(
        onDismissRequest = { MobileData.answer(false) },
        title = { Text(stringResource(R.string.mobile_data_title), color = Am.Navy) },
        text = { Text(stringResource(R.string.mobile_data_body), color = Am.Navy) },
        confirmButton = { DialogPill(stringResource(R.string.mobile_data_use)) { MobileData.answer(true) } },
        dismissButton = { DialogPill(stringResource(R.string.update_not_now), primary = false) { MobileData.answer(false) } },
        containerColor = Am.Card,
    )
}
