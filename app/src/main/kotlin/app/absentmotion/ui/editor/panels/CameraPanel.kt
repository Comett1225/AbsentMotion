package app.absentmotion.ui.editor.panels

import app.absentmotion.model.finiteFloat
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.cameraFov
import app.absentmotion.model.cameraFovOf
import app.absentmotion.model.cameraFovScaled
import app.absentmotion.model.cameraFrame
import app.absentmotion.model.cameraZoomOf
import app.absentmotion.model.formatNumber
import app.absentmotion.model.parseColor
import app.absentmotion.model.valueAt
import app.absentmotion.model.withCameraFov
import app.absentmotion.model.withCameraFrameScaled
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.fadeBackground
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private class Field(@StringRes val label: Int, val path: String, val default: Float, val perDp: Float, val min: Float, val max: Float, val decimals: Int, val unit: String = "") {
    val ref = PropRef(PropRef.Kind.Child, path)
}

private val Angle = Field(R.string.camerapanel_view_angle, "fov", 60f, 0.2f, 0.1f, 179.9f, 1, "º")
private val Zoom = Field(R.string.camerapanel_zoom, "fov", 60f, 0f, 0f, 0f, 0)
private val FocusDistance = Field(R.string.camerapanel_focus_distance, "focus-blur/distance", 500f, 5f, 0f, 100000f, 0)
private val DepthOfField = Field(R.string.camerapanel_depth_of_field, "focus-blur/depth-of-field", 100f, 2f, 0f, 100000f, 0)
private val BlurStrength = Field(R.string.camerapanel_blur_strength, "focus-blur/strength", 0.5f, 0.01f, 0f, 10f, 2)
private val FogColor = PropRef(PropRef.Kind.Child, "fog/color")
private val FogNear = Field(R.string.camerapanel_near, "fog/near-z", 0f, 5f, 0f, 100000f, 0)
private val FogFar = Field(R.string.camerapanel_far, "fog/far-z", 500f, 5f, 0f, 100000f, 0)

private val Tabs = listOf(R.string.camerapanel_tab_camera_view, R.string.camerapanel_focus_blur, R.string.camerapanel_fog)
private val FogSwatches = listOf(0xFFFFFFFF, 0xFFC8CCD8, 0xFF8D90A0, 0xFF000000, 0xFF1E2239, 0xFFF5A623, 0xFFE2574C, 0xFF48C3F5)
private val RowHeight = 40.dp
private val BoxWidth = 76.dp

private fun Layer.number(f: Field): Float = element(f.ref)?.valueAt(t)?.finiteFloat() ?: f.default

private fun Layer.blockOn(name: String): Boolean = node.child(name)?.let { it["enabled"] != "false" } == true

private fun Layer.withBlock(name: String, on: Boolean): Layer =
    copy(node = node.withChild(name, (node.child(name) ?: XmlNode(name)).with("enabled", if (on) null else "false")))

@Composable
internal fun CameraPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var active by remember { mutableIntStateOf(0) }
    val size = max(state.scene.width, state.scene.height)
    val perspective = layer.node["type"] != "orthographic"

    val rows = when (tab) { 0 -> listOf(Angle, Zoom); 1 -> listOf(FocusDistance, DepthOfField, BlurStrength); else -> listOf(FogNear, FogFar) }
    val target = rows.getOrNull(active.coerceAtMost(rows.lastIndex))
    SideEffect { state.keyTarget = target?.let { KeyTarget(it.ref, formatNumber(it.default)) } }

    fun write(f: Field, gesture: Boolean, value: (Float) -> Float) {
        state.editSelected(if (gesture) "camera-${f.path}" else null) { l -> l.withValue(f.ref, formatNumber(value(l.number(f)).coerceIn(f.min, f.max))) }
    }
    fun writeZoom(gesture: Boolean, zoom: (Float) -> Float) {
        val lo = cameraZoomOf(Angle.max, size)
        val hi = cameraZoomOf(Angle.min, size)
        write(Angle, gesture) { fov -> cameraFovOf(zoom(cameraZoomOf(fov, size)).coerceIn(lo, hi), size) }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Tabs.forEachIndexed { i, name ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { tab = i; active = 0 }.fadeBackground(tab == i, PanelDark),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(name), color = if (tab == i) Am.Accent else Am.OnDark, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
        }
        Swap(tab, Modifier.fillMaxSize(), rise = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> {
                        val angle = layer.number(Angle)
                        Row(Modifier.fillMaxWidth().height(RowHeight * 2).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ViewCone(angle, perspective, Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp))
                            Column(Modifier.width(150.dp)) {
                                Choice(stringResource(R.string.camerapanel_perspective), perspective, Modifier.fillMaxWidth()) { state.editSelected { it.copy(node = it.node.with("type", "perspective")) } }
                                Spacer(Modifier.height(6.dp))
                                Choice(stringResource(R.string.camerapanel_orthographic), !perspective, Modifier.fillMaxWidth()) { state.editSelected { it.copy(node = it.node.with("type", "orthographic")) } }
                            }
                        }
                        val angleLabel = stringResource(Angle.label)
                        val zoomLabel = stringResource(Zoom.label)
                        NumberRow(angleLabel, formatFixed(angle, 1) + "º", active == 0, perspective, { d -> active = 0; write(Angle, true) { it + d * Angle.perDp } }, { state.endGesture() },
                            { active = 0; state.askNumber(angleLabel, angle) { v -> write(Angle, false) { v } } })
                        val zoom = cameraZoomOf(angle, size)
                        NumberRow(zoomLabel, "${zoom.toInt()}", active == 1, perspective, { d -> active = 1; writeZoom(true) { it + d * size / 200f } }, { state.endGesture() },
                            { active = 1; state.askNumber(zoomLabel, zoom.toInt().toFloat()) { v -> writeZoom(false) { v } } })
                    }
                    1 -> {
                        SwitchRow(stringResource(R.string.camerapanel_focus_blur), layer.blockOn("focus-blur")) { on -> state.editSelected { it.withBlock("focus-blur", on) } }
                        val plane = layer.location[2] + layer.number(FocusDistance)
                        FieldRows(state, layer, rows, active, layer.blockOn("focus-blur"), { active = it }, ::write,
                            note = { f -> if (f === FocusDistance) stringResource(R.string.camerapanel_focus_plane, formatFixed(plane, 0)) else null })
                    }
                    else -> {
                        SwitchRow(stringResource(R.string.camerapanel_fog), layer.blockOn("fog")) { on -> state.editSelected { it.withBlock("fog", on) } }
                        val on = layer.blockOn("fog")
                        val colour = parseColor(layer.element(FogColor)?.valueAt(layer.t)) ?: 0xFFFFFFFF.toInt()
                        Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp).alpha(if (on) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.color), Modifier.width(96.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
                            FogSwatches.forEach { argb ->
                                val c = argb.toInt()
                                Box(
                                    Modifier.padding(end = 4.dp).size(22.dp).clip(PanelShape).background(Color(c))
                                        .clickable(enabled = on) { state.setColour(FogColor, c) },
                                ) { if (c == colour) Box(Modifier.align(Alignment.Center).size(8.dp).clip(PanelShape).background(if (c == 0xFF000000.toInt()) Color.White else Color.Black)) }
                            }
                        }
                        FieldRows(state, layer, rows, active, on, { active = it }, ::write)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewCone(angle: Float, perspective: Boolean, modifier: Modifier) {
    val line = Am.OnDark
    val accent = Am.Accent
    Canvas(modifier) {
        val h = size.height
        val mid = h / 2
        val body = Size(h * 0.42f, h * 0.3f)
        val bodyTop = mid - body.height / 2
        drawRoundRect(line, Offset(0f, bodyTop), body, CornerRadius(body.height * 0.2f))
        val tip = body.width + body.height * 0.55f
        drawPath(Path().apply {
            moveTo(body.width - 1f, mid)
            lineTo(tip, bodyTop)
            lineTo(tip, bodyTop + body.height)
            close()
        }, line)
        val room = size.width - tip
        val stroke = 1.4.dp.toPx()
        if (perspective) {
            val slope = tan(Math.toRadians(min(angle, 179f) / 4.0)).toFloat()
            val length = min(room, (mid - stroke) / slope)
            val spread = length * slope
            val end = tip + length
            drawLine(accent, Offset(tip, mid), Offset(end, mid - spread), stroke, StrokeCap.Round)
            drawLine(accent, Offset(tip, mid), Offset(end, mid + spread), stroke, StrokeCap.Round)
            drawLine(line.copy(alpha = 0.6f), Offset(end, mid - spread), Offset(end, mid + spread), stroke, StrokeCap.Round)
        } else {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            val spread = body.height / 2
            drawLine(accent, Offset(tip, mid - spread), Offset(size.width, mid - spread), stroke, StrokeCap.Round, dash)
            drawLine(accent, Offset(tip, mid + spread), Offset(size.width, mid + spread), stroke, StrokeCap.Round, dash)
        }
    }
}

@Composable
internal fun CameraSizeTab(state: EditorState, layer: Layer) {
    val (frameW, frameH) = layer.cameraFrame(state.scene.width, state.scene.height)
    val size = max(state.scene.width, state.scene.height)
    val fov = layer.cameraFov
    val drag = remember { floatArrayOf(60f, 0f) }
    val widthLabel = stringResource(R.string.transform_width)
    val heightLabel = stringResource(R.string.transform_height)
    fun openKeypad(isWidth: Boolean) {
        val old = if (isWidth) frameW else frameH
        if (!(old > 0f)) return
        state.keypad = KeypadRequest(listOf(KeypadField(if (isWidth) widthLabel else heightLabel, old)), 0) { v ->
            if (v[0] > 0f) state.editSelected { it.withCameraFrameScaled(v[0] / old) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 42.px), horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                NumberBox(formatFixed(frameW, 1), Am.Accent, Modifier.width(177.px).height(70.px)) { openKeypad(true) }
                Spacer(Modifier.width(14.px))
                Box(Modifier.size(70.px).clip(PanelShape).background(Am.Inactive), contentAlignment = Alignment.Center) {
                    PanelIcon(PanelIcons.Link, Am.OnDark, size = 58.px, description = stringResource(R.string.a11y_keep_proportions))
                }
                Spacer(Modifier.width(14.px))
                NumberBox(formatFixed(frameH, 1), Am.Accent, Modifier.width(177.px).height(70.px)) { openKeypad(false) }
            }
            Row(Modifier.height(23.px)) {
                Caption(widthLabel, Modifier.width(177.px).fillMaxHeight())
                Spacer(Modifier.width(98.px))
                Caption(heightLabel, Modifier.width(177.px).fillMaxHeight())
            }
            Text(stringResource(R.string.camera_frame_caption, formatFixed(cameraZoomOf(fov, size), 1), formatFixed(fov, 1) + "º"),
                Modifier.padding(top = 8.px), color = PanelCaption, fontSize = 11.sp, maxLines = 1, softWrap = false)
        }
        RulerSpinner(
            Am.Accent,
            onDrag = { dx ->
                drag[1] += dx
                state.editSelected(key = "camera-frame") { it.withCameraFov(cameraFovScaled(drag[0], RulerMath.scale(1f, drag[1]))) }
            },
            onEnd = { state.endGesture() },
            modifier = Modifier.padding(top = 215.px).fillMaxWidth().height(323.px),
            onStart = { drag[0] = state.selected?.cameraFov ?: fov; drag[1] = 0f },
            fling = false,
        )
    }
}

@Composable
private fun FieldRows(
    state: EditorState, layer: Layer, rows: List<Field>, active: Int, on: Boolean, select: (Int) -> Unit, write: (Field, Boolean, (Float) -> Float) -> Unit,
    note: @Composable (Field) -> String? = { null },
) {
    rows.forEachIndexed { i, f ->
        val v = layer.number(f)
        val label = stringResource(f.label)
        NumberRow(label, formatFixed(v, f.decimals), active == i, on, { d -> select(i); write(f, true) { it + d * f.perDp } }, { state.endGesture() },
            { select(i); state.askNumber(label, v) { typed -> write(f, false) { typed } } }, note(f))
    }
}

@Composable
private fun NumberRow(
    label: String, text: String, active: Boolean, enabled: Boolean, onDrag: (Float) -> Unit, onEnd: () -> Unit, onTap: () -> Unit, note: String? = null,
) {
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(96.dp)) {
            Text(label, color = Am.OnDark, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false)
            if (note != null) Text(note, color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
        }
        NumberBox(text, if (active) Am.Accent else Am.OnDark, Modifier.width(BoxWidth).height(32.dp), onClick = if (enabled) onTap else null)
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)) {
            if (enabled) RulerSpinner(if (active) Am.Accent else Am.OnDarkDim, onDrag, onEnd, Modifier.fillMaxSize(), tickInset = 6.dp)
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        PanelSwitch(on, onChange = onChange)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(32.dp).clip(PanelShape).background(if (selected) PanelDark else Am.Field).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Am.Accent else Am.OnDark, fontSize = 13.sp, maxLines = 1, softWrap = false) }
}
