package app.absentmotion.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am

private val StripWidth = 18.dp
private val Digits = TextStyle(fontFeatureSettings = "tnum")

internal fun meterStart(width: Dp, height: Dp, reserveEnd: Dp, sceneWidth: Int, sceneHeight: Int): Dp {
    val free = width - minOf(reserveEnd, width / 2)
    val canvas = sceneWidth * minOf(free.value / sceneWidth, height.value / sceneHeight)
    val margin = (minOf(width.value / 2, free.value - canvas / 2) - canvas / 2).dp
    return if (margin >= StripWidth + 8.dp) ((margin - StripWidth) / 2).coerceAtMost(12.dp) else 0.dp
}

@Composable
internal fun MasterMeter(state: EditorState, reserveEnd: Dp, modifier: Modifier = Modifier) {
    val mix = state.engine.audio?.mix ?: return
    val shown by animateFloatAsState(if (state.playing) 1f else 0f, tween(150), label = "master meter")
    val left = remember { MeterBallistics() }
    val right = remember { MeterBallistics() }
    val db by remember { derivedStateOf { MeterBallistics.decibels(maxOf(left.peak, right.peak)) } }
    if (shown == 0f) return
    val label = stringResource(R.string.meter_master)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val start = meterStart(maxWidth, maxHeight, reserveEnd, state.scene.width, state.scene.height)
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = start).width(StripWidth).height((maxHeight - 128.dp).coerceIn(80.dp, 240.dp))
                .alpha(shown).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 6.dp)
                .semantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChannelMeter(state.playing, { mix.masterLeft }, { mix.masterRight }, { false }, Modifier.weight(1f), left, right, bar = 4.dp, labelSize = 8.sp)
            Text(db?.toString() ?: "−∞", Modifier.padding(top = 4.dp).wrapContentWidth(unbounded = true), color = Am.OnDark, fontSize = 9.sp, style = Digits, maxLines = 1, softWrap = false)
            Text("dB", color = Am.OnDarkDim, fontSize = 8.sp, maxLines = 1, softWrap = false)
        }
    }
}
