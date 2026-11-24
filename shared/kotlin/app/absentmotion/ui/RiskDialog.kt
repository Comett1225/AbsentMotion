package app.absentmotion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.RiskAsk
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.text
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.home.DialogPill
import app.absentmotion.ui.theme.Am

internal val LocalRiskAsk = staticCompositionLocalOf<(UiText, () -> Unit) -> Unit> { { _, go -> go() } }

@Composable
internal fun RiskDialog(ask: RiskAsk?, text: Color, warning: Color, container: Color, onClose: (RiskAsk?) -> Unit) {
    ask ?: return
    AlertDialog(
        onDismissRequest = { onClose(null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ExportIcons.Warning, null, Modifier.size(22.dp), tint = warning)
                Text(stringResource(R.string.unlock_title), Modifier.padding(start = 10.dp), color = text)
            }
        },
        text = {
            Text((ask.risks.map { it.reason.text() }.distinct() + stringResource(R.string.unlock_text)).joinToString("\n\n"),
                Modifier.verticalScroll(rememberScrollState()), color = text)
        },
        confirmButton = { DialogPill(stringResource(R.string.unlock_use_anyway)) { onClose(ask) } },
        dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false, editor = container != Am.Card) { onClose(null) } },
        containerColor = container,
    )
}
