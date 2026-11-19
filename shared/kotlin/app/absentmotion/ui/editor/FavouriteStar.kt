package app.absentmotion.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.HoldName
import app.absentmotion.ui.theme.Am

@Composable
internal fun FavouriteStar(on: Boolean, onToggle: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, starSize: Dp = 24.dp, offTint: Color = Am.OnDark, holdName: Boolean = true) {
    val pop = remember { Animatable(1f) }
    val shown = remember { booleanArrayOf(on) }
    LaunchedEffect(on) {
        if (shown[0] == on) return@LaunchedEffect
        shown[0] = on
        pop.animateTo(if (on) 1.3f else 0.88f, tween(if (on) 80 else 60))
        pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, 1500f))
    }
    Box(modifier.toggleable(on, role = Role.Checkbox) { onToggle() }, contentAlignment = Alignment.Center) {
        DeskTip(contentDescription) {
            Icon(if (on) PanelIcons.StarFilled else PanelIcons.Star, contentDescription, Modifier.size(starSize).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = if (on) Am.Favorite else offTint)
        }
        if (holdName) HoldName(contentDescription)
    }
}
