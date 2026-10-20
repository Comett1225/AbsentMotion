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
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

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
        if (text.isBlank()) { message = context.getString(R.string.voice_type_something); return }
        message = ""
        lastText = text; lastVoice = chosen.id; lastSpeed = speed; lastPitch = pitch
        job = scope.launch {
            try {
                val uri = speech.toFile(text.trim(), chosen, speed / 100f, pitch / 100f)
                val media = checkNotNull(audioDeclaration(context, uri)) { context.getString(R.string.voice_unreadable) }
                state.addAudio(MediaDeclaration(media.node.with("title", text.trim().take(40).trim())))
                onClose()
            }
            catch (_: CancellationException) { message = context.getString(R.string.voice_cancelled) }
            catch (e: Exception) { message = e.message ?: context.getString(R.string.voice_unable) }
            finally { job = null }
        }
    }
    fun dismiss() { job?.cancel() ?: onClose() }

    Dialog(::dismiss, sheetDialogProperties()) {
        PhoneScreen {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { dismiss() }.systemBarsPadding().imePadding(), Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(SheetShape).background(Am.Panel).clickable(interactionSource = null, indication = null) {}.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.text_to_speech), Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        BarButton(ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(40.dp), onClick = ::dismiss)
                    }
                    Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCorner).background(Am.Field).padding(10.dp)) {
                        BasicTextField(
                            text, { text = it.take(Speech.MAX_CHARS) }, Modifier.fillMaxSize(),
                            textStyle = TextStyle(color = Am.OnDark, fontSize = 15.sp), cursorBrush = SolidColor(Am.Accent),
                        )
                        if (text.isEmpty()) Text(stringResource(R.string.type_what_the_voice_says), color = Am.OnDarkDim, fontSize = 15.sp)
                    }
                    Box {
                        Line(stringResource(R.string.voice_language), Modifier.clickable(enabled = languages.isNotEmpty()) { menu = "language" }) { Picked(voice?.language) }
                        if (menu == "language") ChoiceList(languages, { menu = null }) { i ->
                            voiceId = all.first { it.language == languages[i] }.id
                        }
                    }
                    Box {
                        Line(stringResource(R.string.voice_voice), Modifier.clickable(enabled = inLanguage.isNotEmpty()) { menu = "voice" }) { Picked(voice?.name) }
                        if (menu == "voice") ChoiceList(inLanguage.map { it.name }, { menu = null }) { voiceId = inLanguage[it].id }
                    }
                    Line(stringResource(R.string.voice_speed)) { Steps(speed, 50..200, 10, "$speed%") { speed = it } }
                    Line(stringResource(R.string.voice_pitch)) { Steps(pitch, 50..200, 10, "$pitch%") { pitch = it } }
                    Column(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 12.dp)) {
                        val note = when {
                            message.isNotEmpty() -> message
                            voices == null -> stringResource(R.string.voice_loading)
                            all.isEmpty() -> stringResource(R.string.voice_none_offline)
                            job != null -> stringResource(R.string.voice_making)
                            else -> ""
                        }
                        Text(note, Modifier.fillMaxWidth().height(36.dp), color = if (message.isNotEmpty()) Warning else Am.OnDarkDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action(stringResource(R.string.voice_preview), Modifier.weight(1f)) { voice?.let { if (text.isNotBlank()) speech.preview(text.trim(), it, speed / 100f, pitch / 100f) } }
                            Action(stringResource(if (job != null) R.string.cancel else R.string.voice_add), Modifier.weight(1f), primary = true) { job?.cancel() ?: add() }
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
