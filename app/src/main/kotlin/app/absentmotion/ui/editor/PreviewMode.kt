package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.library.PreviewQuality
import app.absentmotion.ui.theme.Am

private val PanelBg = Color(0xFF333750)
private val ZoomBg = Color(0xFF191B27)
private val DisabledTint = Color(0xFF818390)

@Composable
internal fun PreviewModePanel(state: EditorState, modifier: Modifier = Modifier) {
    val selected = state.selectedId != null || MultiSelect.ids.isNotEmpty()
    val hasCamera = state.scene.layers.any { it.kind == "camera" }
    Column(modifier.width(55.dp).fillMaxHeight().background(PanelBg).pointerInput(Unit) {}) {
        val cell = Modifier.weight(1f)
        ModeCell(cell, true, { state.pixelMode = !state.pixelMode }) { PixelIcon(state.pixelMode) }
        ModeCell(cell, true, { state.gridMode = !state.gridMode }) { GridIcon(state.gridMode) }
        ModeCell(cell, selected, { state.soloMode = (state.soloMode + 1) % 3 }) { SoloIcon(state.soloMode, selected) }
        ModeCell(cell, hasCamera, { state.cameraMode = !state.cameraMode }) { CameraIcon(state.cameraMode, hasCamera) }
        val quality = state.settings.previewQuality
        ModeCell(cell, true, { state.settings.previewQuality = PreviewQuality.entries[(quality.ordinal + 1) % PreviewQuality.entries.size] }) {
            Text(quality.name, color = if (quality == PreviewQuality.Full) Color.White else Am.Accent, fontSize = 10.sp, maxLines = 1)
        }
        val fullBlur = state.settings.fullMotionBlur
        ModeCell(cell, true, { state.settings.fullMotionBlur = !fullBlur }) {
            Text(if (fullBlur) "Full\nBlur" else "Fast\nBlur", color = if (fullBlur) Am.Accent else Color.White, fontSize = 10.sp,
                lineHeight = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
        }
        ZoomBlock(state)
    }
}

@Composable
private fun ModeCell(modifier: Modifier, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier), Alignment.Center) { icon() }
}

@Composable
private fun ZoomBlock(state: EditorState) {
    Column(Modifier.fillMaxWidth().height(127.dp).padding(5.dp).clip(RoundedCornerShape(3.dp)).background(ZoomBg)) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { state.zoomStep(true) }, Alignment.Center) {
            Canvas(Modifier.size(8.dp)) { units(8f) {
                drawLine(Color.White, Offset(4f, 0.8f), Offset(4f, 7.2f), 1.2f, StrokeCap.Round)
                drawLine(Color.White, Offset(7.2f, 4f), Offset(0.8f, 4f), 1.2f, StrokeCap.Round)
            } }
        }
        Box(Modifier.height(20.dp).fillMaxWidth(), Alignment.Center) {
            Text("${(state.canvasZoom * 100).toInt()}%", color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clickable { state.zoomStep(false) }, Alignment.Center) {
            Canvas(Modifier.size(8.dp)) { units(8f) { drawLine(Color.White, Offset(7.2f, 4f), Offset(0.8f, 4f), 1.2f, StrokeCap.Round) } }
        }
    }
}

private fun DrawScope.units(width: Float, block: DrawScope.() -> Unit) = scale(size.width / width, Offset.Zero, block)

private fun svg(data: String): Path = PathParser().parsePathString(data).toPath()

private const val GRID_GLYPH = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM3 9L21 9M3 15L21 15M9 3L9 21M15 3L15 21"
private const val VIDEO_GLYPH = "M16 13L21.223 16.482A0.5 0.5 0 0 0 22 16.066L22 7.87A0.5 0.5 0 0 0 21.248 7.438L16 10.5M4 6L14 6A2 2 0 0 1 16 8L16 16A2 2 0 0 1 14 18L4 18" +
            "A2 2 0 0 1 2 16L2 8A2 2 0 0 1 4 6Z"
private const val VIDEO_OFF_GLYPH = "M10.66 6L14 6A2 2 0 0 1 16 8L16 10.5L21.248 7.438A0.5 0.5 0 0 1 22 7.87L22 16.066M16 16A2 2 0 0 1 14 18L4 18A2 2 0 0 1 2 16L2 8A2 2 0 0 1 4 6L6 6M2 2" +
            "L22 22"
private const val ZOOM_GLYPH = "M3 11A8 8 0 1 0 19 11A8 8 0 1 0 3 11ZM21 21L16.65 16.65M11 8L11 14M8 11L14 11"

private fun round(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
private fun PixelIcon(on: Boolean) {
    val colour = if (on) Am.Accent else Color.White
    Canvas(Modifier.size(24.dp)) { units(24f) {
        for (row in 0..2) for (col in 0..2) {
            val at = Offset(3.5f + col * 6f, 3.5f + row * 6f)
            if (on && col <= row) drawRect(colour, at, Size(5f, 5f))
            else drawRect(colour, at + Offset(0.6f, 0.6f), Size(3.8f, 3.8f), style = Stroke(1.2f))
        }
    } }
}

@Composable
private fun GridIcon(on: Boolean) {
    val colour = if (on) Am.Accent else Color.White
    Canvas(Modifier.size(24.dp)) { units(24f) { drawPath(svg(GRID_GLYPH), colour, style = round(1.5f)) } }
}

@Composable
private fun SoloIcon(mode: Int, enabled: Boolean) {
    val colour = if (mode == 0) Color.White else Am.Accent
    val top = svg("M12.5 3.4L23 8.8L12.5 14.2L2 8.8Z")
    val under = Path.combine(PathOperation.Difference, svg("M2 13.6L12.5 19L23 13.6L12.5 8.2Z"), top)
    Canvas(Modifier.size(25.dp, 22.dp).alpha(if (enabled) 1f else DimAlpha)) { units(25f) {
        drawPath(under, colour, alpha = when (mode) { 0 -> 0.7f; 1 -> 0.3f; else -> 0.8f })
        drawPath(top, colour, alpha = when (mode) { 0 -> 0.92f; 1 -> 1f; else -> 0.5f })
        drawPath(top, colour, style = round(1.3f))
    } }
}

@Composable
private fun CameraIcon(on: Boolean, enabled: Boolean) {
    val colour = if (!enabled) DisabledTint else if (on) Am.Accent else Color.White
    val glyph = svg(if (on) VIDEO_GLYPH else VIDEO_OFF_GLYPH)
    Canvas(Modifier.size(27.dp, 23.dp)) { units(27f) {
        withTransform({ translate(13.5f, 11.5f); scale(1.1f, 1.1f, Offset.Zero); translate(-12f, -12f) }) {
            if (on) drawPath(glyph, colour)
            drawPath(glyph, colour, style = round(1.5f))
        }
    } }
}

@Composable
internal fun CanvasZoomIcon(on: Boolean, guide: Boolean) {
    val glyph = if (guide) Color.Black.copy(alpha = 0.4f) else if (on) Am.Accent else Color.White
    Canvas(Modifier.size(28.dp)) { units(28f) {
        drawRoundRect(if (guide) Color.White.copy(alpha = 0.2f) else Color(0xFF2C3046), size = Size(28f, 28f), cornerRadius = CornerRadius(3f))
        withTransform({ translate(14f, 14f); scale(0.8f, 0.8f, Offset.Zero); translate(-12f, -12f) }) {
            drawPath(svg(ZOOM_GLYPH), glyph, style = round(1.9f))
        }
    } }
}
