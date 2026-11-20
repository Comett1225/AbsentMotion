package app.absentmotion.ui.adaptive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.editor.LocalWide

class AdaptiveSpace(val width: Dp, val height: Dp) {
    val landscape: Boolean get() = width > height
    val wide: Boolean get() = width >= WideFrom
    val columns: Int get() = ((width + Gap) / (ColumnMin + Gap)).toInt().coerceIn(1, 3)
}

val LocalAdaptiveSpace = staticCompositionLocalOf<AdaptiveSpace?> { null }

val WideFrom = 720.dp
private val ColumnMin = 340.dp
private val Gap = 24.dp
private val CardMaxWidth = 1100.dp
private val CardMaxHeight = 800.dp

fun adaptiveWindowSize(width: Dp, height: Dp): DpSize = DpSize(minOf(width, CardMaxWidth), minOf(height, CardMaxHeight))

@Composable
fun AdaptiveWindow(content: @Composable () -> Unit) {
    AdaptiveWindow(CardMaxWidth, CardMaxHeight, content = content)
}

@Composable
internal fun Modifier.scrimTap(onTap: () -> Unit): Modifier {
    val tap by rememberUpdatedState(onTap)
    return pointerInput(Unit) { detectTapGestures(onLongPress = {}) { tap() } }
}

@Composable
fun AdaptiveWindow(maxCardWidth: Dp, maxCardHeight: Dp, heightRatio: Float? = null, corner: Dp = 16.dp, onDismiss: (() -> Unit)? = null, content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .then(if (onDismiss != null) Modifier.scrimTap(onDismiss) else Modifier.pointerInput(Unit) { detectTapGestures { } })
            .systemBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val width = minOf(maxWidth, maxCardWidth)
        val height = minOf(maxHeight, maxCardHeight, heightRatio?.let { width * it } ?: maxCardHeight)
        Box(
            Modifier.size(width, height).shadow(24.dp, RoundedCornerShape(corner)).clip(RoundedCornerShape(corner))
                .then(if (onDismiss != null) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
        ) {
            CompositionLocalProvider(LocalWide provides false, LocalAdaptiveSpace provides AdaptiveSpace(width, height)) { content() }
        }
    }
}

@Composable
fun AdaptivePanes(
    modifier: Modifier = Modifier,
    sideWeight: Float = 0.42f,
    side: @Composable ColumnScope.() -> Unit,
    main: @Composable ColumnScope.() -> Unit,
) {
    val space = LocalAdaptiveSpace.current
    if (space != null && space.wide) Row(modifier, horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Column(Modifier.weight(1f - sideWeight).fillMaxHeight(), content = main)
        Column(Modifier.weight(sideWeight).fillMaxHeight(), content = side)
    } else Column(modifier) {
        main()
        side()
    }
}

@Composable
fun AdaptiveSections(sections: List<@Composable ColumnScope.() -> Unit>, modifier: Modifier = Modifier) {
    val columns = LocalAdaptiveSpace.current?.columns ?: 1
    if (columns <= 1 || sections.size <= 1) Column(modifier) { sections.forEach { it() } }
    else Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        val perColumn = (sections.size + columns - 1) / columns
        sections.chunked(perColumn).forEach { part -> Column(Modifier.weight(1f)) { part.forEach { it() } } }
        repeat(columns - (sections.size + perColumn - 1) / perColumn) { Box(Modifier.weight(1f)) }
    }
}

private val LogoOrange = Color(0xFFFF5A36)

private val LogoMark: ImageVector = ImageVector.Builder("AbsentMotionLogo", 108.dp, 108.dp, 108f, 108f).apply {
    listOf(
        "M31.447,45.792 A24,24 0 0 1 74.785,42",
        "M44.238,75.925 A24,24 0 0 1 30.131,56.509",
        "M64.521,75.571 A24,24 0 0 1 54.838,77.985",
    ).forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 7f, strokeLineCap = StrokeCap.Round) }
    addPath(addPathNodes("M69.412,68.776 a3.5,3.5 0 1 0 7,0 a3.5,3.5 0 1 0 -7,0 z"), fill = SolidColor(Color.White))
    addPath(addPathNodes("M47,54 a7,7 0 1 0 14,0 a7,7 0 1 0 -14,0 z"), fill = SolidColor(Color.White))
}.build()

@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(LogoOrange)) {
        Image(LogoMark, null, Modifier.fillMaxSize())
    }
}

@Composable
fun adaptiveWindowIsWide(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { size.width.toDp() }
    return size.width > size.height && width - 48.dp >= WideFrom
}
