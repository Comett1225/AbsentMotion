package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatNumber
import app.absentmotion.model.parseColor
import app.absentmotion.model.valueAt
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.fadeBackground
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

private class Field(val label: String, val path: String, val default: Float, val perDp: Float, val min: Float, val max: Float, val decimals: Int, val unit: String = "") {
    val ref = PropRef(PropRef.Kind.Child, path)
}

private val Angle = Field("View Angle", "fov", 60f, 0.2f, 0.1f, 179.9f, 1, "º")
private val Zoom = Field("Zoom", "fov", 60f, 0f, 0f, 0f, 0)
private val FocusDistance = Field("Focus Distance", "focus-blur/distance", 500f, 5f, 0f, 100000f, 0)
private val DepthOfField = Field("Depth of Field", "focus-blur/depth-of-field", 100f, 2f, 0f, 100000f, 0)
private val BlurStrength = Field("Blur Strength", "focus-blur/strength", 0.5f, 0.01f, 0f, 10f, 2)
private val FogColor = PropRef(PropRef.Kind.Child, "fog/color")
private val FogNear = Field("Near", "fog/near-z", 0f, 5f, 0f, 100000f, 0)
private val FogFar = Field("Far", "fog/far-z", 500f, 5f, 0f, 100000f, 0)

private val Tabs = listOf("Camera View", "Focus Blur", "Fog")
private val FogSwatches = listOf(0xFFFFFFFF, 0xFFC8CCD8, 0xFF8D90A0, 0xFF000000, 0xFF1E2239, 0xFFF5A623, 0xFFE2574C, 0xFF48C3F5)
private val RowHeight = 40.dp
private val BoxWidth = 76.dp

private fun Layer.number(f: Field): Float = element(f.ref)?.valueAt(t)?.toFloatOrNull() ?: f.default

private fun zoomOf(fov: Float, size: Int): Float = (size / (2 * tan(Math.toRadians(fov / 2.0)))).toFloat()

private fun fovOf(zoom: Float, size: Int): Float = Math.toDegrees(2 * atan(size / (2.0 * zoom))).toFloat()

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

    fun write(f: Field, value: Float, gesture: Boolean) {
        val v = value.coerceIn(f.min, f.max)
        state.editSelected(if (gesture) "camera-${f.path}" else null) { it.withValue(f.ref, formatNumber(v)) }
    }
    fun writeZoom(zoom: Float, gesture: Boolean) {
        val lo = zoomOf(Angle.max, size)
        val hi = zoomOf(Angle.min, size)
        write(Angle, fovOf(zoom.coerceIn(lo, hi), size), gesture)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Tabs.forEachIndexed { i, name ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { tab = i; active = 0 }.fadeBackground(tab == i, PanelDark),
                    contentAlignment = Alignment.Center,
                ) { Text(name, color = if (tab == i) Am.Accent else Am.OnDark, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
        }
        Swap(tab, Modifier.fillMaxSize(), rise = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> {
                        Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Choice("Perspective", perspective, Modifier.weight(1f)) { state.editSelected { it.copy(node = it.node.with("type", "perspective")) } }
                            Box(Modifier.width(8.dp))
                            Choice("Orthographic", !perspective, Modifier.weight(1f)) { state.editSelected { it.copy(node = it.node.with("type", "orthographic")) } }
                        }
                        val angle = layer.number(Angle)
                        NumberRow(Angle.label, formatFixed(angle, 1) + "º", active == 0, perspective, { active = 0; write(Angle, it, true) }, { state.endGesture() },
                            { active = 0; state.askNumber(Angle.label, angle) { write(Angle, it, false) } })
                        val zoom = zoomOf(angle, size)
                        NumberRow(Zoom.label, "${zoom.toInt()}", active == 1, perspective, { active = 1; writeZoom(zoomOf(layer.number(Angle), size) + it * size / 200f, true) }, { state.endGesture() },
                            { active = 1; state.askNumber(Zoom.label, zoom.toInt().toFloat()) { writeZoom(it, false) } })
                    }
                    1 -> {
                        SwitchRow("Focus Blur", layer.blockOn("focus-blur")) { on -> state.editSelected { it.withBlock("focus-blur", on) } }
                        FieldRows(state, layer, rows, active, layer.blockOn("focus-blur"), { active = it }, ::write)
                    }
                    else -> {
                        SwitchRow("Fog", layer.blockOn("fog")) { on -> state.editSelected { it.withBlock("fog", on) } }
                        val on = layer.blockOn("fog")
                        val colour = parseColor(layer.element(FogColor)?.valueAt(layer.t)) ?: 0xFFFFFFFF.toInt()
                        Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp).alpha(if (on) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
                            Text("Color", Modifier.width(96.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
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
private fun FieldRows(state: EditorState, layer: Layer, rows: List<Field>, active: Int, on: Boolean, select: (Int) -> Unit, write: (Field, Float, Boolean) -> Unit) {
    rows.forEachIndexed { i, f ->
        val v = layer.number(f)
        NumberRow(f.label, formatFixed(v, f.decimals), active == i, on, { select(i); write(f, layer.number(f) + it * f.perDp, true) }, { state.endGesture() },
            { select(i); state.askNumber(f.label, v) { write(f, it, false) } })
    }
}

@Composable
private fun NumberRow(
    label: String, text: String, active: Boolean, enabled: Boolean, onDrag: (Float) -> Unit, onEnd: () -> Unit, onTap: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, softWrap = false)
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
