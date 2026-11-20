package app.absentmotion.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        confirmButton = { TextButton({ MobileData.answer(true) }) { Text(stringResource(R.string.mobile_data_use), color = Am.Navy) } },
        dismissButton = { TextButton({ MobileData.answer(false) }) { Text(stringResource(R.string.update_not_now), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
