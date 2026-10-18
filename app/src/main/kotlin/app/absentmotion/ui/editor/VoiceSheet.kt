package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Speech
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

data class SpeechVoice(val id: String, val name: String, val language: String)

private var lastText = ""
private var lastVoice: String? = null
private var lastSpeed = 100
private var lastPitch = 100

@Composable
internal fun VoiceSheet(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speech = remember { Speech(context) }
    DisposableEffect(Unit) { onDispose { speech.close() } }
    val voices by produceState<List<SpeechVoice>?>(null) { value = runCatching { speech.voices() }.getOrDefault(emptyList()) }
    var text by remember { mutableStateOf(lastText) }
    var voiceId by remember { mutableStateOf(lastVoice) }
    var speed by remember { mutableStateOf(lastSpeed) }
    var pitch by remember { mutableStateOf(lastPitch) }
    var menu by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    val all = voices.orEmpty()
    val languages = all.map { it.language }.distinct().sortedBy { if (it == Locale.getDefault().displayName) "" else it }
    val voice = all.firstOrNull { it.id == voiceId }
        ?: all.firstOrNull { it.language == Locale.getDefault().displayName } ?: all.firstOrNull()
    val inLanguage = all.filter { it.language == voice?.language }

    fun add() {
        val chosen = voice ?: return
        if (text.isBlank()) { message = "Type something to read"; return }
        message = ""
        lastText = text; lastVoice = chosen.id; lastSpeed = speed; lastPitch = pitch
        job = scope.launch {
            try {
                val uri = speech.toFile(text.trim(), chosen, speed / 100f, pitch / 100f)
                val media = checkNotNull(audioDeclaration(context, uri)) { "The voice could not be read back" }
                state.addAudio(MediaDeclaration(media.node.with("title", text.trim().take(40).trim())))
                onClose()
            }
            catch (_: CancellationException) { message = "Cancelled" }
            catch (e: Exception) { message = e.message ?: "Unable to make the voice" }
            finally { job = null }
        }
    }
    fun dismiss() { job?.cancel() ?: onClose() }

    Dialog(::dismiss, sheetDialogProperties()) {
        PhoneScreen {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { dismiss() }.systemBarsPadding().imePadding(), Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(SheetShape).background(Am.Panel).clickable(interactionSource = null, indication = null) {}.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Text to Speech", Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        BarButton(ChromeIcons.Close, "Close", Modifier.size(40.dp), onClick = ::dismiss)
                    }
                    Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCorner).background(Am.Field).padding(10.dp)) {
                        BasicTextField(
                            text, { text = it.take(Speech.MAX_CHARS) }, Modifier.fillMaxSize(),
                            textStyle = TextStyle(color = Am.OnDark, fontSize = 15.sp), cursorBrush = SolidColor(Am.Accent),
                        )
                        if (text.isEmpty()) Text("Type what the voice says", color = Am.OnDarkDim, fontSize = 15.sp)
                    }
                    Box {
                        Line("Language", Modifier.clickable(enabled = languages.isNotEmpty()) { menu = "language" }) { Picked(voice?.language) }
                        if (menu == "language") ChoiceList(languages, { menu = null }) { i ->
                            voiceId = all.first { it.language == languages[i] }.id
                        }
                    }
                    Box {
                        Line("Voice", Modifier.clickable(enabled = inLanguage.isNotEmpty()) { menu = "voice" }) { Picked(voice?.name) }
                        if (menu == "voice") ChoiceList(inLanguage.map { it.name }, { menu = null }) { voiceId = inLanguage[it].id }
                    }
                    Line("Speed") { Steps(speed, 50..200, 10, "$speed%") { speed = it } }
                    Line("Pitch") { Steps(pitch, 50..200, 10, "$pitch%") { pitch = it } }
                    Column(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 12.dp)) {
                        val note = when {
                            message.isNotEmpty() -> message
                            voices == null -> "Loading voices…"
                            all.isEmpty() -> "No offline voices on this device"
                            job != null -> "Making the voice…"
                            else -> ""
                        }
                        Text(note, Modifier.fillMaxWidth().height(36.dp), color = if (message.isNotEmpty()) Warning else Am.OnDarkDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action("Preview", Modifier.weight(1f)) { voice?.let { if (text.isNotBlank()) speech.preview(text.trim(), it, speed / 100f, pitch / 100f) } }
                            Action(if (job != null) "Cancel" else "Add", Modifier.weight(1f), primary = true) { job?.cancel() ?: add() }
                        }
                    }
                }
            }
        }
    }
}

private val RoundedCorner = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

@Composable
private fun Picked(label: String?) {
    Text(label ?: "—", Modifier.widthIn(max = 220.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Icon(ChromeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.OnDark)
}
