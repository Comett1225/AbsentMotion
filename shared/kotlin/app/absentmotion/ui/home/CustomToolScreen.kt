package app.absentmotion.ui.home

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.keepCopy
import app.absentmotion.export.ExportKind
import app.absentmotion.export.formatBytes
import app.absentmotion.library.CommunityClient
import app.absentmotion.library.CommunityDamagedException
import app.absentmotion.library.CommunityFeed
import app.absentmotion.library.CommunityKind
import app.absentmotion.library.MediaKeeper
import app.absentmotion.library.PresetStore
import app.absentmotion.library.installCommunityTool
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.platform.rememberMediaPicker
import app.absentmotion.tools.AppToolHost
import app.absentmotion.tools.InstalledTool
import app.absentmotion.tools.InstalledTools
import app.absentmotion.tools.PickedMedia
import app.absentmotion.tools.ToolInput
import app.absentmotion.tools.ToolProblem
import app.absentmotion.tools.checkEffects
import app.absentmotion.tools.readPicked
import app.absentmotion.tools.runTool
import app.absentmotion.tools.toolRunFolder
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun rememberToolIcon(file: File): ImageBitmap? = produceState<ImageBitmap?>(null, file.path, InstalledTools.revision) {
    value = withContext(Dispatchers.IO) {
        runCatching { file.readBytes().let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
    }
}.value

@Composable
internal fun ToolIcon(file: File, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val icon = rememberToolIcon(file)
    Box(modifier.size(size).clip(CircleShape).background(Am.Navy), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun CustomToolScreen(installed: InstalledTool, editor: EditorState?, onClose: () -> Unit) {
    val context = LocalContext.current
    val tool = installed.tool
    val work = rememberToolWork()
    val presets by produceState(emptyList<Pair<String, String>>()) {
        value = if (tool.inputs.any { it.type == "preset" }) withContext(Dispatchers.IO) { PresetStore(context).list().map { it.id to it.title } } else emptyList()
    }
    val layers = editor?.scene?.layers.orEmpty().reversed()
    val values = remember(tool) {
        mutableStateMapOf<String, Any>().apply {
            tool.inputs.forEach { input ->
                when (input.type) {
                    "layer" -> (editor?.selectedId ?: layers.firstOrNull()?.id)?.let { put(input.id, it) }
                    else -> input.default?.let { put(input.id, it) }
                }
            }
        }
    }
    LaunchedEffect(presets) { tool.inputs.filter { it.type == "preset" && it.id !in values }.forEach { input -> presets.firstOrNull()?.let { values[input.id] = it.first } } }
    val missing = tool.inputs.firstOrNull { it.id !in values }
    val back = { if (work.running) work.cancel() else onClose() }
    ToolSheet(tool.name, back, bottom = {
        ToolBar(
            work, missing?.let { stringResource(R.string.custom_tool_missing, it.label) }, false,
            (work.step as? ToolStep.Done)?.let { stringResource(R.string.custom_tool_done, formatBytes(it.bytes)) }.orEmpty(),
            R.string.custom_tool_running, stringResource(R.string.custom_tool_run), HomeIcons.ToolsTab, missing == null,
        ) {
            val chosen = values.toMap()
            work.start { progress ->
                try {
                    if (tool.effects.isNotEmpty()) checkEffects(tool) { id -> editor?.engine?.effects?.value?.find(id) }
                } catch (e: ToolProblem) {
                    throw ToolProblem(context.getString(R.string.custom_tool_cannot_run, e.message.orEmpty()))
                }
                val host = AppToolHost(context, editor?.settings?.defaultLayerDuration ?: 2, { id -> editor?.engine?.effects?.value?.find(id) }, editor?.let { keeper(context, it) })
                val run = runTool(tool, chosen, host, editor?.scene, editor?.frame ?: 0, withContext(Dispatchers.IO) { toolRunFolder(context, tool.id) }, progress)
                if (run.edits.isEmpty()) run.result
                else {
                    withContext(Dispatchers.Main) {
                        editor?.edit { s -> run.edits.fold(s) { acc, change -> change(acc) } }
                        editor?.addOpen = false
                        onClose()
                    }
                    null
                }
            }
        }
    }) {
        Spacer(Modifier.height(16.dp))
        Text(tool.description, color = Am.HomeDim, fontSize = 15.sp, lineHeight = 21.sp)
        for (input in tool.inputs) {
            ToolInputField(input, values, !work.running, layers.map { it.id to it.label.ifBlank { it.kind } }, presets) { work.reset() }
        }
        val done = work.step as? ToolStep.Done
        if (done != null) {
            val mime = done.result.mime
            when {
                mime.startsWith("audio/") -> SoundResult(done.result, formatBytes(done.bytes))
                mime == "image/gif" -> ToolResult(done.result, ExportKind.Gif)
                done.result.video -> ToolResult(done.result, ExportKind.Video)
                else -> ToolResult(done.result, ExportKind.Frame)
            }
        }
        ToolAbout(installed, enabled = !work.running, onRemoved = onClose)
        Spacer(Modifier.height(24.dp))
    }
}

private fun keeper(context: android.content.Context, state: EditorState): suspend (MediaDeclaration, Boolean) -> MediaDeclaration = { media, made ->
    if (made) withContext(Dispatchers.IO) { MediaKeeper(state.store, { u -> context.contentResolver.openInputStream(Uri.parse(u)) }).keep(state.projectId, media, state.scene) }
    else state.keepCopy(context, media)
}

@Composable
private fun ToolInputField(
    input: ToolInput, values: MutableMap<String, Any>, enabled: Boolean, layers: List<Pair<Long, String>>, presets: List<Pair<String, String>>, changed: () -> Unit,
) {
    fun set(value: Any) { values[input.id] = value; changed() }
    when (input.type) {
        "video", "image" -> {
            Spacer(Modifier.height(16.dp))
            MediaInput(input, values[input.id] as? PickedMedia, enabled) { set(it) }
        }
        "number" -> {
            Label(input.label)
            var text by remember(input.id) { mutableStateOf(plain((values[input.id] as? Double) ?: input.min)) }
            ToolField(text, enabled, KeyboardType.Decimal, input.unit, Modifier.width(160.dp)) { typed ->
                text = typed.filter { it.isDigit() || it == '.' || it == '-' }.take(12)
                text.toDoubleOrNull()?.let { set(it) }
            }
        }
        "text" -> {
            Label(input.label)
            ToolField(values[input.id] as? String ?: "", enabled, KeyboardType.Text, "", Modifier.fillMaxWidth()) { typed -> set(typed.replace('\n', ' ').take(input.maxLength)) }
        }
        "choice" -> {
            Label(input.label)
            Chips(input.options.map { it.label }, (values[input.id] as? Int) ?: 0, enabled) { set(it) }
        }
        "switch" -> Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(input.label, Modifier.weight(1f), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            RefSwitch(values[input.id] as? Boolean ?: false, enabled) { set(it) }
        }
        "colour" -> {
            Label(input.label)
            val hex = values[input.id] as? String ?: "#ffffff"
            var text by remember(input.id) { mutableStateOf(hex) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val swatch = runCatching { Color(0xFF000000L or hex.removePrefix("#").toLong(16)) }.getOrDefault(Color.White)
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(swatch))
                Spacer(Modifier.width(10.dp))
                ToolField(text, enabled, KeyboardType.Ascii, "", Modifier.width(160.dp)) { typed ->
                    text = ("#" + typed.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }).take(7)
                    if (text.length == 7) set(text.lowercase())
                }
            }
        }
        "layer" -> {
            Label(input.label)
            if (layers.isEmpty()) Text(stringResource(R.string.custom_tool_no_layers), color = Am.HomeDim, fontSize = 14.sp)
            else Chips(layers.map { it.second }, layers.indexOfFirst { it.first == values[input.id] }, enabled) { set(layers[it].first) }
        }
        "preset" -> {
            Label(input.label)
            if (presets.isEmpty()) Text(stringResource(R.string.custom_tool_no_presets), color = Am.HomeDim, fontSize = 14.sp)
            else Chips(presets.map { it.second }, presets.indexOfFirst { it.first == values[input.id] }, enabled) { set(presets[it].first) }
        }
    }
}

private fun plain(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

@Composable
private fun MediaInput(input: ToolInput, picked: PickedMedia?, enabled: Boolean, onPicked: (PickedMedia) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf(false) }
    val video = input.type == "video"
    val picker = rememberMediaPicker(videoOnly = video) { uri ->
        if (uri != null) scope.launch {
            val found = readPicked(context, uri)?.takeIf { it.source.video == video && (!video || it.source.durationUs > 0) }
            problem = found == null
            if (found != null) onPicked(found)
        }
    }
    Text(input.label, Modifier.padding(bottom = 8.dp), color = Am.HomeMid, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    PickCard(picked, problem, enabled, if (video) R.string.tools_choose_video else R.string.custom_tool_choose_picture, if (video) R.string.tools_pick_video else R.string.compress_unreadable) { picker.launch() }
}

@Composable
private fun ToolField(text: String, enabled: Boolean, keyboard: KeyboardType, unit: String, modifier: Modifier, onText: (String) -> Unit) {
    Row(
        modifier.height(42.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            text, onText, Modifier.weight(1f), enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp), cursorBrush = SolidColor(Am.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        )
        if (unit.isNotEmpty()) Text(unit, color = Am.HomeDim, fontSize = 15.sp)
    }
}

@Composable
private fun ToolAbout(installed: InstalledTool, enabled: Boolean, onRemoved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val tool = installed.tool
    SectionTitle(stringResource(R.string.custom_tool_by, tool.creator, tool.version))
    ToolPermissionsList(tool.permissions)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(stringResource(R.string.custom_tool_update), Modifier.weight(1f), HomeIcons.Download, primary = false, enabled = enabled && !busy) {
            busy = true
            scope.launch {
                val message = try {
                    withContext(Dispatchers.IO) {
                        val client = CommunityClient(context.cacheDir) { TrustedHttp(CommunityFeed.source) }
                        val item = client.index().items.firstOrNull { it.kind == CommunityKind.Tool && it.name == tool.id }
                        if (item == null || item.sha256 == sha256Of(installed.file)) R.string.custom_tool_up_to_date
                        else { installCommunityTool(context, client, item); R.string.custom_tool_updated }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: CommunityDamagedException) {
                    R.string.community_download_damaged
                } catch (_: Exception) {
                    R.string.community_download_failed
                }
                busy = false
                Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
            }
        }
        BigButton(stringResource(R.string.custom_tool_remove), Modifier.weight(1f), HomeIcons.Delete, primary = false, enabled = enabled && !busy) {
            InstalledTools.remove(context, tool.id)
            Toast.makeText(context, context.getString(R.string.custom_tool_removed), Toast.LENGTH_SHORT).show()
            onRemoved()
        }
    }
}

@Composable
internal fun ToolPermissionsList(permissions: List<String>) {
    Text(stringResource(R.string.custom_tool_can), color = Am.HomeMid, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    for (permission in permissions) {
        val text = when (permission) {
            "media.read" -> R.string.custom_tool_permission_media_read
            "media.write" -> R.string.custom_tool_permission_media_write
            else -> R.string.custom_tool_permission_project_write
        }
        Text("•  " + stringResource(text), Modifier.padding(top = 4.dp), color = Am.Navy, fontSize = 15.sp, lineHeight = 21.sp)
    }
}
