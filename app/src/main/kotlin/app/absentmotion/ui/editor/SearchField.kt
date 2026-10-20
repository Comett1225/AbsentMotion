package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.Locale

private val Marks = Regex("\\p{M}+")
private val Spaces = Regex("\\s+")

private fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, "").lowercase(Locale.ROOT)

fun matchesSearch(query: String, vararg texts: String): Boolean {
    val words = folded(query).split(Spaces).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val fields = texts.map(::folded)
    return words.all { word -> fields.any { it.contains(word) } }
}

@Composable
internal fun debounced(text: String, delayMs: Long = 250): String {
    var settled by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text.isNotBlank()) delay(delayMs)
        settled = text
    }
    return settled
}

@Composable
internal fun SearchField(
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    hint: String = "Search",
    background: Color = Am.Field,
    content: Color = Am.OnDark,
    faint: Color = Am.OnDarkDim,
    autoFocus: Boolean = false,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) requester.requestFocus() }
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(TextIcons.Search, null, Modifier.size(16.dp), tint = faint)
        BasicTextField(
            text, onChange, Modifier.weight(1f).padding(start = 6.dp).focusRequester(requester),
            textStyle = TextStyle(color = content, fontSize = 14.sp), singleLine = true, cursorBrush = SolidColor(content),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(hint, color = faint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Box(Modifier.size(32.dp).then(if (text.isEmpty()) Modifier else Modifier.clickable { onChange(""); focus.clearFocus() }), Alignment.Center) {
            if (text.isNotEmpty()) Icon(ChromeIcons.CloseThick, "Clear search", Modifier.size(16.dp), tint = content)
        }
    }
}

@Composable
internal fun NoMatches(query: String, what: String, modifier: Modifier, color: Color = Am.OnDarkDim, size: TextUnit = 13.sp) {
    Box(modifier, Alignment.Center) {
        Text("No $what match “${query.trim()}”", Modifier.padding(horizontal = 12.dp), color = color, fontSize = size, maxLines = 3,
            textAlign = TextAlign.Center)
    }
}
