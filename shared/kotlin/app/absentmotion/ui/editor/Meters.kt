package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.audio.LiveMix
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

private val MixRed = Color(0xFFFF3B30)
private val MixYellow = Color(0xFFFFCC00)
private val MixGreen = Color(0xFF34C759)
private val Digits = TextStyle(fontFeatureSettings = "tnum")
private val BarWidth = 5.dp
private val BarGap = 2.dp
private val StripWidth = 18.dp

internal class MeterBallistics {
    var shown by mutableFloatStateOf(0f)
        private set
    var peak by mutableFloatStateOf(0f)
        private set
    var clip by mutableFloatStateOf(0f)
        private set
    private var age = 0f

    fun reset() { shown = 0f; peak = 0f; clip = 0f; age = 0f }

    fun step(level: Float, dt: Float) {
        shown = maxOf(position(level), shown * exp(-dt / 0.11f))
        if (shown >= peak) { peak = shown; age = 0f }
        else { age += dt; if (age > 0.6f) peak = maxOf(shown, peak - 1.5f * dt) }
        clip = if (level >= 1f) 0.5f else maxOf(0f, clip - dt)
    }

    companion object {
        fun position(level: Float): Float = if (level > 0f) (1f + 20f * log10(level) / 48f).coerceIn(0f, 1f) else 0f

        fun decibels(position: Float): Int? = if (position <= 0f) null else ((position - 1f) * 48f).roundToInt()
    }
}

internal fun meterStart(width: Dp, height: Dp, reserveEnd: Dp, sceneWidth: Int, sceneHeight: Int): Dp {
    val free = width - minOf(reserveEnd, width / 2)
    val canvas = sceneWidth * minOf(free.value / sceneWidth, height.value / sceneHeight)
    val margin = (minOf(width.value / 2, free.value - canvas / 2) - canvas / 2).dp
    return if (margin >= StripWidth + 8.dp) ((margin - StripWidth) / 2).coerceAtMost(12.dp) else 0.dp
}

@Composable
internal fun MasterMeter(state: EditorState, reserveEnd: Dp, modifier: Modifier = Modifier) {
    val mix = state.engine.audio?.mix ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val start = meterStart(maxWidth, maxHeight, reserveEnd, state.scene.width, state.scene.height)
        MeterReadout(
            state, mix,
            Modifier.align(Alignment.TopStart).padding(start = start, top = 72.dp).width(StripWidth).height((maxHeight - 84.dp).coerceIn(80.dp, 240.dp))
                .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 6.dp),
            bar = 4.dp, labelSize = 8.sp,
        )
    }
}

@Composable
internal fun DeskMasterMeter(state: EditorState, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.background(Am.Panel).padding(top = 10.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
        val mix = state.engine.audio?.mix ?: return@Box
        MeterReadout(state, mix, Modifier.heightIn(max = height - 18.dp).fillMaxHeight(), bar = BarWidth, labelSize = 9.sp)
    }
}

@Composable
private fun MeterReadout(state: EditorState, mix: LiveMix, modifier: Modifier, bar: Dp, labelSize: TextUnit) {
    val left = remember { MeterBallistics() }
    val right = remember { MeterBallistics() }
    val db by remember { derivedStateOf { MeterBallistics.decibels(maxOf(left.peak, right.peak)) } }
    val label = stringResource(R.string.meter_master)
    Column(modifier.semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        ChannelMeter(state.playing, { mix.masterLeft }, { mix.masterRight }, Modifier.weight(1f), left, right, bar = bar, labelSize = labelSize)
        Text(db?.toString() ?: "−∞", Modifier.padding(top = 4.dp).wrapContentWidth(unbounded = true), color = Am.OnDark, fontSize = (labelSize.value + 1f).sp, style = Digits, maxLines = 1, softWrap = false)
        Text("dB", color = Am.OnDarkDim, fontSize = labelSize, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun ChannelMeter(
    playing: Boolean, left: () -> Float, right: () -> Float, modifier: Modifier,
    l: MeterBallistics = remember { MeterBallistics() }, r: MeterBallistics = remember { MeterBallistics() },
    bar: Dp = BarWidth, labelSize: TextUnit = 9.sp,
) {
    val readLeft by rememberUpdatedState(left)
    val readRight by rememberUpdatedState(right)
    LaunchedEffect(playing) {
        l.reset()
        r.reset()
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            l.step(readLeft(), dt)
            r.step(readRight(), dt)
        }
    }
    val label = Am.OnDarkDim
    Column(modifier.width(bar * 2 + BarGap), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.weight(1f), Arrangement.spacedBy(BarGap)) {
            Bar(l, Modifier.width(bar).fillMaxHeight())
            Bar(r, Modifier.width(bar).fillMaxHeight())
        }
        Row(Modifier.padding(top = 3.dp), Arrangement.spacedBy(BarGap)) {
            Text(stringResource(R.string.meter_left_short), Modifier.width(bar).wrapContentWidth(unbounded = true), color = label, fontSize = labelSize, maxLines = 1, softWrap = false)
            Text(stringResource(R.string.meter_right_short), Modifier.width(bar).wrapContentWidth(unbounded = true), color = label, fontSize = labelSize, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun Bar(meter: MeterBallistics, modifier: Modifier) {
    val well = Am.Deepest
    Spacer(modifier.drawWithCache {
        val ramp = Brush.verticalGradient(0f to MixRed, 0.3f to MixYellow, 1f to MixGreen, startY = 0f, endY = size.height)
        val corner = CornerRadius(2.dp.toPx())
        val tick = 2.dp.toPx()
        val cap = 3.dp.toPx()
        onDrawBehind {
            val h = size.height
            drawRoundRect(well, cornerRadius = corner)
            val shown = meter.shown
            if (shown > 0f) drawRect(ramp, Offset(0f, h * (1f - shown)), Size(size.width, h * shown))
            val peak = meter.peak
            if (peak > 0f) drawRect(Color.White.copy(alpha = 0.7f), Offset(0f, (h * (1f - peak)).coerceIn(0f, h - tick)), Size(size.width, tick))
            if (meter.clip > 0f) drawRect(MixRed, Offset.Zero, Size(size.width, cap))
        }
    })
}
