package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.stretchedTiming
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.panels.ThinSlider
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val LeastPercent = 25
private const val MostPercent = 400
private val FieldShape = RoundedCornerShape(6.dp)

@Composable
internal fun StretchSheet(state: EditorState, ids: Set<Long>, onClose: () -> Unit) {
    var percent by remember { mutableIntStateOf(100) }
    var keypad by remember { mutableStateOf<KeypadRequest?>(null) }
    val title = stringResource(R.string.multi_stretch_timing)
    val shorter = stringResource(R.string.multi_stretch_shorter)
    val longer = stringResource(R.string.multi_stretch_longer)
    fun set(value: Float) { percent = value.roundToInt().coerceIn(LeastPercent, MostPercent) }
    Dialog(onClose, sheetDialogProperties()) {
        PhoneScreen(onDismiss = onClose, sheet = true) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onClose).systemBarsPadding(), if (app.absentmotion.platform.PlatformFeatures.desktop) Alignment.Center else Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(SheetShape).background(Am.Panel).clickable(interactionSource = null, indication = null) {}.padding(16.dp)) {
                    Text(title, color = Am.OnDark, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(pluralStringResource(R.plurals.structure_layers_selected, ids.size, ids.size.toString()), Modifier.padding(top = 4.dp, bottom = 12.dp), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
                    Row(Modifier.fillMaxWidth().height(52.dp).clip(FieldShape).background(Am.Field), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(56.dp).fillMaxHeight().clickable { set((percent - 1) / 10 * 10f) }, Alignment.Center) {
                            Icon(ChromeIcons.Minus, shorter, Modifier.size(20.dp), tint = Am.Accent)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            keypad = KeypadRequest(listOf(KeypadField(title, percent.toFloat(), "%", LeastPercent.toFloat(), MostPercent.toFloat())), 0) { set(it[0]) }
                        }, Alignment.Center) {
                            Text("$percent%", color = Am.OnDark, fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 1)
                        }
                        Box(Modifier.width(56.dp).fillMaxHeight().clickable { set((percent / 10 + 1) * 10f) }, Alignment.Center) {
                            Icon(ChromeIcons.Plus, longer, Modifier.size(20.dp), tint = Am.Accent)
                        }
                    }
                    ThinSlider(ln(percent / 100f) / ln(4f), { set(100 * 4f.pow(it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), valueRange = -1f..1f, inactive = Am.Field)
                    Row(Modifier.fillMaxWidth()) {
                        Text(shorter, Modifier.weight(1f), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
                        Text(longer, color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
                    }
                    Text(stringResource(R.string.multi_stretch_note), Modifier.padding(vertical = 12.dp), color = Am.OnDark, fontSize = 13.sp, lineHeight = 17.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Action(stringResource(R.string.cancel), Modifier.weight(1f), onClick = onClose)
                        Action(stringResource(R.string.apply), Modifier.weight(1f), primary = true) {
                            state.edit { it.stretchedTiming(ids, percent) }
                            onClose()
                        }
                    }
                }
                Swap(keypad, Modifier.fillMaxSize(), rise = 0.dp) { keypad?.let { ValueKeypad(it, { keypad = null }, Modifier.fillMaxSize()) } }
            }
        }
    }
}
