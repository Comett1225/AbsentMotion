package app.absentmotion.ui.editor

import app.absentmotion.platform.Toast
import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.applyPreset
import app.absentmotion.engine.PreviewView
import app.absentmotion.library.PresetStore
import app.absentmotion.library.SavedPreset
import app.absentmotion.model.Scene
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val RowShape = RoundedCornerShape(12.dp)
private val Orange get() = Am.Accent
private val Grip get() = Am.Caption
private val Chip get() = Am.OnDark

@Composable
fun PresetScreen(state: EditorState) {
    val context = LocalContext.current
    val store = remember { PresetStore(context) }
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    val loaded by produceState<List<SavedPreset>?>(null, rev) { value = withContext(Dispatchers.IO) { store.list() } }
    val presets = loaded.orEmpty()
    var chosen by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SavedPreset?>(null) }
    var deleting by remember { mutableStateOf<SavedPreset?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val base = remember { state.selectedId?.let { state.scene.layer(it) } }
    BackHandler { state.presetsOpen = false }
    if (base == null) {
        SideEffect { state.presetsOpen = false }
        return
    }
    val current = presets.firstOrNull { it.id == chosen }
    val shown = remember(current) { current?.layer?.let { applyPreset(base, it) } ?: base }
    val scene = remember(shown) {
        val node = state.scene.node
        val layer = shown.node.with("parent", null).with("clippingMask", null).with("hidden", null)
        Scene(node.copy(children = node.children.filter { it.name !in Scene.LAYER_TAGS } + layer, attrs = node.attrs + ("totalTime" to "${shown.endTime}")))
    }
    val length = (shown.endTime - shown.startTime).coerceAtLeast(1)
    val fps = scene.fps
    fun timecode(ms: Int) = TimelineMath.timecode((ms * fps / 1000f).roundToInt(), fps)

    LaunchedEffect(scene) { state.engine.setScene(scene) }
    DisposableEffect(Unit) { onDispose { state.engine.setScene(state.scene) } }
    val fit = if (box.width == 0) 1f else min(box.width.toFloat() / scene.width, box.height.toFloat() / scene.height)
    LaunchedEffect(fit, pos, scene) {
        if (box.width > 0) state.engine.setView(PreviewView(shown.startTime + pos, fit, (box.width - scene.width * fit) / 2, (box.height - scene.height * fit) / 2, 0, -1))
    }
    LaunchedEffect(playing, chosen, length) {
        if (!playing) return@LaunchedEffect
        if (pos >= length) pos = 0
        val from = pos
        val start = withFrameNanos { it }
        while (true) {
            val next = from + ((withFrameNanos { it } - start) / 1_000_000).toInt()
            if (next >= length) { pos = length; playing = false; break }
            pos = next
        }
    }

    Column(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding()) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Box(Modifier.size(48.dp).padding(start = 4.dp).clickable { state.presetsOpen = false }, Alignment.Center) {
                Icon(ChromeIcons.Back, stringResource(R.string.preset_back), Modifier.size(24.dp), tint = Am.OnDark)
            }
            Text(stringResource(R.string.try_a_preset), Modifier.align(Alignment.Center), color = Am.OnDark, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(
                Modifier.align(Alignment.CenterEnd).height(48.dp).width(52.dp).alpha(if (current == null) DimAlpha else 1f)
                    .then(if (current == null) Modifier else Modifier.clickable {
                        state.applyPreset(current)
                        state.presetsOpen = false
                        Toast.makeText(context, context.getString(R.string.preset_applied), Toast.LENGTH_SHORT).show()
                    }),
                Alignment.Center,
            ) { Text(stringResource(R.string.apply), color = Am.OnDark, fontSize = 18.sp, maxLines = 1) }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(352.dp).clip(RowShape).background(Am.PreviewBg)
                .onSizeChanged { box = it }.clickable { playing = !playing },
        ) {
            app.absentmotion.platform.SceneSurface(state.engine, 1, box, Modifier.fillMaxSize())
            if (!playing) Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color(0x66000000)), Alignment.Center) {
                Icon(ChromeIcons.Play, stringResource(R.string.preset_play), Modifier.size(32.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        Scrubber(
            pos.toFloat() / length, timecode(pos), "00:00:00", timecode(length),
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(80.dp),
        ) { playing = false; pos = (it * length).roundToInt() }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.padding(horizontal = 16.dp).fillMaxWidth().weight(1f)) {
            if (loaded?.isEmpty() == true) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_presets_saved_yet), color = Am.OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.save_a_layer_s_settings_and), Modifier.padding(vertical = 12.dp),
                        color = Am.OnDarkDim, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }
            }
            items(presets, key = { it.id }) { preset ->
                Row(
                    Modifier.padding(bottom = 12.dp).fillMaxWidth().height(48.dp).clip(RowShape)
                        .background(if (preset.id == chosen) Am.Field else Am.Button)
                        .clickable { chosen = preset.id; pos = 0; playing = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        preset.title, Modifier.weight(1f).padding(start = 8.dp), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.size(48.dp).clickable { menuFor = preset.id }, Alignment.Center) {
                        Icon(PanelIcons.More, stringResource(R.string.preset_more), Modifier.size(24.dp), tint = Am.OnDark)
                        Presence(menuFor == preset.id) {
                            MenuPopup(DropOrRise, { menuFor = null }) {
                                MenuRow(stringResource(R.string.rename)) { menuFor = null; renaming = preset }
                                MenuRow(stringResource(R.string.delete)) { menuFor = null; deleting = preset }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { preset ->
        RenamePresetDialog(preset.title, onDone = { title ->
            scope.launch {
                if (!withContext(Dispatchers.IO) { runCatching { store.rename(preset.id, title) }.isSuccess }) Toast.makeText(context, context.getString(R.string.preset_rename_failed), Toast.LENGTH_SHORT).show()
                rev++
            }
            renaming = null
        }, onDismiss = { renaming = null })
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.template_delete_preset)) },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch { withContext(Dispatchers.IO) { store.delete(preset.id) }; rev++ }
                    if (chosen == preset.id) { chosen = null; pos = pos.coerceAtMost((base.endTime - base.startTime).coerceAtLeast(1)) }
                    Toast.makeText(context, context.getString(R.string.preset_deleted), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.delete), color = Am.Accent) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
            containerColor = Am.Field, titleContentColor = Am.OnDark,
        )
    }
}

@Composable
private fun Scrubber(progress: Float, now: String, start: String, end: String, modifier: Modifier, onSeek: (Float) -> Unit) {
    val seekCurrent by rememberUpdatedState(onSeek)
    val thumb = 10.dp
    var width by remember { mutableIntStateOf(0) }
    Box(modifier.onSizeChanged { width = it.width }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val room = (size.width - thumb.toPx()).coerceAtLeast(1f)
                    fun seek(x: Float) = seekCurrent(((x - thumb.toPx() / 2) / room).coerceIn(0f, 1f))
                    seek(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull { it.id == down.id }?.let { seek(it.position.x); it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            val y = 40.dp.toPx()
            drawLine(Orange, Offset(0f, y), Offset(size.width, y), 6.dp.toPx(), StrokeCap.Round)
            val left = progress * (size.width - thumb.toPx())
            drawRoundRect(Grip, Offset(left, y - 15.5.dp.toPx()), Size(thumb.toPx(), 31.dp.toPx()), CornerRadius(5.dp.toPx()))
            for (i in 0..2) {
                val x = left + (2.2f + 2.8f * i).dp.toPx()
                drawLine(Am.TopBar, Offset(x, y - 8.dp.toPx()), Offset(x, y + 8.dp.toPx()), 1.dp.toPx())
            }
        }
        Box(
            Modifier.offset {
                val chip = 58.dp.roundToPx()
                IntOffset((progress * (width - thumb.toPx()) + thumb.toPx() / 2 - chip / 2f).coerceIn(0f, (width - chip).toFloat().coerceAtLeast(0f)).roundToInt(), 4.dp.roundToPx())
            }.size(58.dp, 16.dp).clip(RoundedCornerShape(4.dp)).background(Chip),
            Alignment.Center,
        ) { Text(now, color = Am.TopBar, fontSize = 13.sp, maxLines = 1) }
        Text(start, Modifier.align(Alignment.BottomStart), color = Am.OnDarkDim, fontSize = 13.sp)
        Text(end, Modifier.align(Alignment.BottomEnd), color = Am.OnDarkDim, fontSize = 13.sp)
    }
}

@Composable
internal fun RenamePresetDialog(title: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rename_preset)) },
        text = {
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Am.Panel).padding(12.dp),
                textStyle = TextStyle(color = Am.OnDark, fontSize = 16.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content), singleLine = true, cursorBrush = SolidColor(Am.Accent),
                decorationBox = { field -> Box { if (text.isEmpty()) Text(title, color = Am.OnDarkDim, fontSize = 16.sp); field() } },
            )
        },
        confirmButton = { TextButton({ onDone(text.ifBlank { title }) }) { Text(stringResource(R.string.done), color = Am.Accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
    )
}

@Composable
internal fun PresetSavedDialog(preset: SavedPreset, store: PresetStore, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    if (renaming) {
        RenamePresetDialog(preset.title, onDone = { title ->
            scope.launch {
                if (!withContext(Dispatchers.IO) { runCatching { store.rename(preset.id, title) }.isSuccess }) Toast.makeText(context, context.getString(R.string.preset_rename_failed), Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.preset_stored)) },
        confirmButton = { TextButton({ renaming = true }) { Text(stringResource(R.string.name_it), color = Am.Accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close), color = Am.Accent) } },
        containerColor = Am.Field, titleContentColor = Am.OnDark,
    )
}
