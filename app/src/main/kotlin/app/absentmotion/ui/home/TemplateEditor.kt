package app.absentmotion.ui.home

import app.absentmotion.platform.Uri
import app.absentmotion.platform.Toast
import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import app.absentmotion.platform.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.PreviewView
import app.absentmotion.library.AppSettings
import app.absentmotion.library.TemplateStore
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.MediaFillMode
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.TimelineMath
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private val Rounded = RoundedCornerShape(12.dp)
private const val ReplaceHint = 1
private const val ScaleHint = 2

object EmbedRequest {
    var templateId by mutableStateOf<String?>(null)
}

private fun layersOf(scene: XmlNode): Sequence<XmlNode> = scene.children.asSequence().filter { it.name in Scene.LAYER_TAGS }
    .flatMap { sequenceOf(it) + (it.child("scene")?.let(::layersOf) ?: emptySequence()) }

private fun XmlNode.mapLayers(change: (XmlNode) -> XmlNode): XmlNode = copy(
    children = children.map { c ->
        if (c.name !in Scene.LAYER_TAGS) c else change(c).let { l -> l.child("scene")?.let { l.withChild("scene", it.mapLayers(change)) } ?: l }
    },
)

private fun XmlNode.int(name: String, default: Int) = this[name]?.toIntOrNull() ?: default

internal class Slot(val uri: String, val video: Boolean, val length: Int, val start: Int, val end: Int, val mode: MediaFillMode)

internal class Choice(val media: MediaDeclaration?, val trim: Int, val mode: MediaFillMode)

private class Trimming(val slot: Slot, val media: MediaDeclaration)

internal fun slotsOf(scene: Scene): List<Slot> =
    layersOf(scene.node).filter { it["fillType"] == "media" && (it["fillImage"] ?: it["fillVideo"]) != null }
        .sortedWith(compareBy({ it.int("startTime", 0) }, { it.int("endTime", 0) }))
        .groupBy { it["fillImage"] ?: it["fillVideo"]!! }
        .map { (uri, layers) ->
            fun span(l: XmlNode) = l.int("endTime", 0) - l.int("startTime", 0)
            val video = layers.first()["fillImage"] == null
            val length = if (video) layers.maxOf { minOf(it.int("outTime", Int.MAX_VALUE), it.int("inTime", 0) + span(it)) } - layers.minOf { it.int("inTime", 0) }
                else layers.maxOf(::span)
            Slot(uri, video, length, layers.minOf { it.int("startTime", 0) }, layers.maxOf { it.int("endTime", 0) },
                MediaFillMode.entries.firstOrNull { it.xml == layers.first()["mediaFillMode"] } ?: MediaFillMode.Fill)
        }

internal fun applied(template: Scene, slots: List<Slot>, choices: Map<String, Choice>): Scene {
    var node = template.node
    for (slot in slots) {
        val choice = choices[slot.uri] ?: continue
        val old = template.media(slot.uri)
        val minIn = layersOf(template.node).filter { (it["fillImage"] ?: it["fillVideo"]) == slot.uri }.minOf { it.int("inTime", 0) }
        node = node.mapLayers { l -> if ((l["fillImage"] ?: l["fillVideo"]) == slot.uri) swap(l, slot, choice, old, minIn) else l }
        choice.media?.let { new ->
            node = Scene(node.copy(children = node.children.filterNot { it.name == "media" && it["uri"] == slot.uri })).withMedia(new).node
        }
    }
    return Scene(node)
}

private fun swap(layer: XmlNode, slot: Slot, choice: Choice, old: MediaDeclaration?, minIn: Int): XmlNode {
    var n = layer.with("mediaFillMode", choice.mode.xml)
    val media = choice.media ?: return n
    val video = media.type.startsWith("video/")
    n = n.with("fillImage", media.uri.takeUnless { video }).with("fillVideo", media.uri.takeIf { video })
    if (!video) {
        n = n.with("inTime", null).with("outTime", null).with("speed", null).with("loop", null).withChild("speedMap", null)
    } else {
        val length = layer.int("endTime", 0) - layer.int("startTime", 0)
        val wasVideo = layer["fillVideo"] != null
        val from = if (wasVideo) layer.int("inTime", 0) - minIn else 0
        val to = if (wasVideo) layer.int("outTime", layer.int("inTime", 0) + length) - minIn else length
        val duration = (media.duration / 1000).toInt()
        val start = if (duration < slot.length) min(from, duration) else from + choice.trim
        val end = if (duration < slot.length) min(to, duration) else to + choice.trim
        n = n.with("inTime", start.takeIf { it != 0 }?.toString()).with("outTime", "$end").with("loop", null)
    }
    return turned(n, media.orientation - (old?.orientation ?: 0))
}

private fun turned(node: XmlNode, degrees: Int): XmlNode {
    val layer = Layer(node)
    if (degrees % 360 == 0 || layer.keyframes(PropRef.Rotation).isNotEmpty()) return node
    var out = layer.withTransform("rotation", layer.transform("rotation", 0f)[0] + degrees)
    val size = out.prop("size")
    if (degrees % 180 != 0 && out.shape == ".rect" && size != null && size.size >= 2) out = out.withProp("size", "vec2", size[1], size[0])
    return out.node
}

private fun clipScene(media: MediaDeclaration, fps: Int): Scene {
    val turned = media.orientation == 90 || media.orientation == 270
    val duration = (media.duration / 1000).toInt()
    var layer = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "$duration", "fillType" to "media",
        "fillVideo" to media.uri, "outTime" to "$duration", "mediaFillMode" to "fill", "s" to ".rect")))
    val w = if (turned) media.height else media.width
    val h = if (turned) media.width else media.height
    layer = layer.withTransform("location", w / 2f, h / 2f, 0f).withProp("size", "vec2", media.width / 2f, media.height / 2f)
    if (media.orientation != 0) layer = layer.withTransform("rotation", media.orientation.toFloat())
    return Scene.create("", w, h, fps, 0xFF000000.toInt()).add(layer, listOf(media))
}

@Composable
fun TemplateEditor(templateId: String, engine: EngineThread, settings: AppSettings, onClose: () -> Unit, onContinue: (Scene) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val template = remember(templateId) { TemplateStore(context).load(templateId) }
    if (template == null) {
        SideEffect(onClose)
        return
    }
    val slots = remember(template) { slotsOf(template) }
    var choices by remember { mutableStateOf<Map<String, Choice>>(emptyMap()) }
    var selected by remember { mutableStateOf<Slot?>(null) }
    var trimming by remember { mutableStateOf<Trimming?>(null) }
    var trimStart by remember { mutableIntStateOf(0) }
    var asking by remember { mutableIntStateOf(0) }
    var frozen by remember { mutableStateOf(false) }
    var hints by remember { mutableIntStateOf(settings.templateHints) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val working = remember(choices) { applied(template, slots, choices) }
    val clipScene = remember(trimming) { trimming?.let { clipScene(it.media, template.fps) } }
    val shown = clipScene ?: working
    val total = (if (trimming != null) shown.totalTime else template.totalTime).coerceAtLeast(1)
    val time = if (trimming != null) trimStart else pos
    val current by rememberUpdatedState(selected)

    fun choose(slot: Slot, media: MediaDeclaration, trim: Int) { choices = choices + (slot.uri to Choice(media, trim, MediaFillMode.Fill)) }
    val picker = app.absentmotion.platform.rememberMediaPicker { uri ->
        val slot = current
        if (uri == null || slot == null) return@rememberMediaPicker
        scope.launch {
            val media = mediaDeclaration(context, uri)
            val duration = (media?.duration ?: -1) / 1000
            when {
                media == null -> Toast.makeText(context, "Unsupported file type", Toast.LENGTH_SHORT).show()
                !media.type.startsWith("video/") -> choose(slot, media, 0)
                slot.length - 300 > duration -> { frozen = true; choose(slot, media, 0) }
                slot.length + 600 < duration -> { trimStart = 0; playing = false; trimming = Trimming(slot, media) }
                else -> choose(slot, media, 0)
            }
        }
    }

    BackHandler { if (trimming != null) trimming = null else if (choices.isEmpty()) onClose() else asking = 1 }
    LaunchedEffect(shown) { engine.setScene(shown.toXml()) }
    DisposableEffect(Unit) { onDispose { engine.setPlaying(false) } }
    LaunchedEffect(playing) { engine.setPlaying(playing) }
    val fit = if (box.width == 0) 1f else min(box.width.toFloat() / shown.width, box.height.toFloat() / shown.height)
    LaunchedEffect(fit, time, shown) {
        if (box.width > 0) engine.setView(PreviewView(time, fit, (box.width - shown.width * fit) / 2, (box.height - shown.height * fit) / 2, 0, -1))
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (pos >= total) pos = 0
        val from = pos
        val start = withFrameNanos { it }
        while (true) {
            val next = from + ((withFrameNanos { it } - start) / 1_000_000).toInt()
            if (next >= total) { pos = total; playing = false; break }
            pos = next
        }
    }
    fun timecode(ms: Int) = TimelineMath.timecode((ms * template.fps / 1000f).roundToInt(), template.fps)

    Column(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding().pointerInput(Unit) {}) {
        Spacer(Modifier.height(16.dp))
        val clip = trimming
        if (clip == null) {
            TopBar("Use Template", ChromeIcons.Back, "Back", "Next", { if (choices.isEmpty()) onClose() else asking = 1 }, { asking = 2 })
        } else {
            TopBar("Drag across to select", HomeIcons.Close, "Close", "Done", { trimming = null }, {
                choose(clip.slot, clip.media, trimStart)
                trimming = null
            })
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().weight(1f).clip(Rounded).background(Am.PreviewBg).onSizeChanged { box = it }
            .clickable(enabled = clip == null) { playing = !playing }) {
            app.absentmotion.platform.SceneSurface(engine, false, box, Modifier.fillMaxSize())
            if (clip == null && !playing) Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color(0x66000000)), Alignment.Center) {
                Icon(ChromeIcons.Play, "Play", Modifier.size(32.dp), tint = Color.White)
            }
            val hint = when {
                clip != null -> null
                selected != null && hints and ReplaceHint == 0 -> "Tap again to swap in your own media" to ReplaceHint
                choices.values.any { it.media != null } && hints and ScaleHint == 0 -> "Tap the corner icon to change scaling" to ScaleHint
                else -> null
            }
            if (hint != null) Hint(hint.first, Modifier.align(Alignment.BottomCenter)) { hints = hints or hint.second; settings.templateHints = hints }
        }
        Spacer(Modifier.height(12.dp))
        if (clip == null) {
            Track(time.toFloat() / total, selected?.let { it.start.toFloat() / total to it.end.toFloat() / total }, Modifier.padding(horizontal = 16.dp)) {
                playing = false
                pos = (it * total).roundToInt()
            }
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Text(timecode(pos), Modifier.weight(1f), color = Am.OnDarkDim, fontSize = 13.sp)
                Text(timecode(total), color = Am.OnDarkDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Media slots", Modifier.padding(horizontal = 16.dp), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LazyRow(Modifier.fillMaxWidth().height(96.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(slots, key = { _, s -> s.uri }) { _, slot ->
                    val choice = choices[slot.uri]
                    val uri = choice?.media?.uri ?: slot.uri
                    val video = choice?.media?.type?.startsWith("video/") ?: slot.video
                    val picture by produceState<ImageBitmap?>(null, uri, video) { value = app.absentmotion.platform.slotPicture(context, uri, video) }
                    SlotCell(
                        picture, slot === selected, choice?.mode ?: slot.mode,
                        onTap = {
                            if (slot === selected) picker.launch()
                            else { selected = slot; playing = false; pos = slot.start }
                        },
                        onScale = {
                            val next = MediaFillMode.entries[((choice?.mode ?: slot.mode).ordinal + 1) % MediaFillMode.entries.size]
                            choices = choices + (slot.uri to Choice(choice?.media, choice?.trim ?: 0, next))
                        },
                    )
                }
            }
            Text(
                if (selected == null) "Tap to pick media" else "Tap again to swap",
                Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Am.OnDarkDim, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
        } else {
            val needed = clip.slot.length
            val duration = (clip.media.duration / 1000).toInt()
            Track(0f, trimStart.toFloat() / duration to (trimStart + needed).toFloat() / duration, Modifier.padding(horizontal = 16.dp), window = true) {
                trimStart = (it * duration - needed / 2f).roundToInt().coerceIn(0, (duration - needed).coerceAtLeast(0))
            }
            Text(
                "${timecode(trimStart)} - ${timecode(trimStart + needed)}",
                Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Am.OnDarkDim, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(166.dp))
        }
    }

    if (asking == 1) Ask(
        "Leave the editor?", "Leaving now discards your changes.\n\nStay here to swap the template's media for your own.",
        "Keep editing", { asking = 0 }, "Exit", { asking = 0; onClose() },
    )
    if (asking == 2) Ask(
        "Finished swapping in your media?", "Once you continue you can't return to this step.\n\nStay here to swap the template's media for your own.",
        "Keep Editing the Template", { asking = 0 }, "Go to Timeline", { asking = 0; playing = false; onContinue(working) },
    )
    if (frozen) AlertDialog(
        onDismissRequest = { frozen = false }, title = { Text("Video frozen") },
        text = { Text("Your video ended early, so its last frame is held.\nSwap in a longer clip for a cleaner result.") },
        confirmButton = { TextButton({ frozen = false }) { Text("OK", color = Am.Accent) } },
        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
    )
}

@Composable
private fun TopBar(title: String, icon: ImageVector, description: String, action: String, onBack: () -> Unit, onAction: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.size(48.dp).clickable(onClick = onBack), Alignment.Center) { Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark) }
        Text(title, Modifier.align(Alignment.Center), color = Am.OnDark, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Box(Modifier.align(Alignment.CenterEnd).height(48.dp).clickable(onClick = onAction).padding(horizontal = 16.dp), Alignment.Center) {
            Text(action, color = Am.Accent, fontSize = 18.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier, onOk: () -> Unit) {
    Row(modifier.padding(12.dp).clip(Rounded).background(Am.Field).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false), color = Am.OnDark, fontSize = 15.sp)
        TextButton(onOk) { Text("OK", color = Am.Accent) }
    }
}

@Composable
private fun Track(progress: Float, lit: Pair<Float, Float>?, modifier: Modifier, window: Boolean = false, onSeek: (Float) -> Unit) {
    Canvas(
        modifier.fillMaxWidth().height(32.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                do {
                    val event = awaitPointerEvent()
                    event.changes.first().let { onSeek((it.position.x / size.width).coerceIn(0f, 1f)); it.consume() }
                } while (event.changes.any { it.pressed })
            }
        },
    ) {
        drawRoundRect(Am.Button, Offset.Zero, size, CornerRadius(6.dp.toPx()))
        lit?.let { (a, b) ->
            drawRoundRect(Am.Accent.copy(alpha = if (window) 0.5f else 0.35f), Offset(a * size.width, 0f), Size(((b - a) * size.width).coerceAtLeast(2.dp.toPx()), size.height), CornerRadius(6.dp.toPx()))
        }
        if (!window) drawLine(Am.OnDark, Offset(progress * size.width, 0f), Offset(progress * size.width, size.height), 2.dp.toPx())
    }
}

@Composable
private fun SlotCell(picture: ImageBitmap?, selected: Boolean, mode: MediaFillMode, onTap: () -> Unit, onScale: () -> Unit) {
    Box(
        Modifier.size(96.dp).clip(Rounded).background(Color.Black).then(if (selected) Modifier.border(2.dp, Am.Accent, Rounded) else Modifier).clickable(onClick = onTap),
    ) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (selected) Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color(0x99000000)), Alignment.Center) {
            Icon(HomeIcons.Replace, "Replace", Modifier.size(24.dp), tint = Color.White)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onScale), Alignment.Center) {
            Icon(when (mode) { MediaFillMode.Fill -> HomeIcons.CropFill; MediaFillMode.Fit -> HomeIcons.CropFit; MediaFillMode.Stretch -> HomeIcons.CropStretch }, "Scale", Modifier.size(20.dp), tint = Color.White)
        }
    }
}

@Composable
private fun Ask(title: String, body: String, stay: String, onStay: () -> Unit, go: String, onGo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onGo) { Text(go, color = Am.Accent) } },
        dismissButton = { TextButton(onStay) { Text(stay, color = Am.Accent) } },
        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
    )
}
