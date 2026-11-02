package app.absentmotion.ui.home

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.theme.Am

internal fun Modifier.handCursor(): Modifier = if (PlatformFeatures.desktop) pointerHoverIcon(PointerIcon.Hand) else this

@Composable
internal fun Modifier.hoverFill(radius: Dp = 0.dp, color: Color = Am.PressHome): Modifier {
    if (!PlatformFeatures.desktop) return this
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return this.hoverable(source).drawBehind { if (hovered) drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx())) }
}

@Composable
internal fun Modifier.deskPress(radius: Dp = 0.dp): Modifier = this.hoverFill(radius).handCursor()

internal object DeskModals {
    var open by mutableIntStateOf(0)
    var fade by mutableFloatStateOf(0f)
    const val ScrimAlpha = 0.45f
}
