package app.absentmotion.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskTyping
import app.absentmotion.platform.Hint
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion

@Composable
internal fun DeskModal(
    onClose: () -> Unit,
    width: Dp,
    height: Dp? = null,
    onEnter: (() -> Unit)? = null,
    dismissOnScrim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    DeskBack.Handler(true, onClose)
    DisposableEffect(Unit) {
        DeskModals.open++
        onDispose { DeskModals.open--; if (DeskModals.open == 0) DeskModals.fade = 0f }
    }
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, Motion.fast(), label = "scrim")
    SideEffect { DeskModals.fade = fade }
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val enter by rememberUpdatedState(onEnter)
    val dismiss by rememberUpdatedState(onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = DeskModals.ScrimAlpha * fade))
            .pointerInput(Unit) { detectTapGestures { if (dismissOnScrim) dismiss() } }
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { event -> DeskTyping.active > 0 && confirmOnEnter(event, enter) }
            .onKeyEvent { event -> confirmOnEnter(event, enter) },
        Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            val w = minOf(width, maxWidth)
            val cardModifier = if (height != null) Modifier.size(w, minOf(height, maxHeight)) else Modifier.width(w).heightIn(max = maxHeight)
            Column(
                cardModifier.alpha(fade).shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Am.Card)
                    .border(1.dp, Am.HomeLine, RoundedCornerShape(16.dp)).pointerInput(Unit) { detectTapGestures { } },
                content = content,
            )
        }
    }
}

private fun confirmOnEnter(event: KeyEvent, action: (() -> Unit)?): Boolean {
    if (action == null || event.type != KeyEventType.KeyDown || event.key != Key.Enter && event.key != Key.NumPadEnter) return false
    action()
    return true
}

@Composable
internal fun DeskDialogHeader(title: String, onClose: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (subtitle != null) Text(subtitle, Modifier.padding(start = 12.dp).weight(1f), color = Am.HomeDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Spacer(Modifier.weight(1f))
        DeskIconButton(HomeIcons.Close, androidx.compose.ui.res.stringResource(app.absentmotion.R.string.action_close), onClose, tooltip = "Esc")
    }
    DeskDivider()
}

@Composable
internal fun DeskDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Am.HomeLine))
}

@Composable
internal fun DeskIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tooltip: String? = description, size: Dp = 36.dp, tint: Color = Am.Navy, enabled: Boolean = true) {
    val body: @Composable () -> Unit = {
        Box(
            modifier.size(size).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(10.dp)).deskPress(10.dp).clickable(enabled = enabled, onClick = onClick),
            Alignment.Center,
        ) { Icon(icon, description, Modifier.size(20.dp), tint = tint) }
    }
    if (tooltip != null) Hint(tooltip, body) else body()
}

@Composable
internal fun DeskButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false,
    icon: ImageVector? = null, enabled: Boolean = true, minWidth: Dp = 0.dp, height: Dp = 36.dp,
) {
    val fill = when { primary -> Am.Accent; danger -> Am.Card; else -> Am.Card }
    val ink = when { primary -> Am.OnAccent; danger -> Am.HomeAccent; else -> Am.Navy }
    val edge = when { primary -> Color.Transparent; danger -> Am.HomeAccent.copy(alpha = 0.5f); else -> Am.HomeLine }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.height(height).widthIn(min = minWidth).alpha(if (enabled) 1f else 0.45f).clip(shape).background(fill).border(1.dp, edge, shape)
            .deskPress(10.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        Arrangement.Center, Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

internal class AtPosition(private val at: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.left + at.x).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top + at.y).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

internal class BelowAnchor(private val alignEnd: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + 4
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height - 4).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
internal fun DeskMenuSurface(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(width).shadow(12.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Am.Card)
            .border(1.dp, Am.HomeLine, RoundedCornerShape(10.dp)).padding(vertical = 4.dp).verticalScroll(rememberScrollState()),
        content = content,
    )
}

@Composable
internal fun DeskPopupMenu(provider: PopupPositionProvider, onDismiss: () -> Unit, width: Dp = 220.dp, content: @Composable ColumnScope.() -> Unit) {
    DeskBack.Handler(true, onDismiss)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        DeskMenuSurface(width, content)
    }
}

@Composable
internal fun DeskMenuItem(label: String, onClick: () -> Unit, icon: ImageVector? = null, danger: Boolean = false, selected: Boolean = false, hint: String? = null) {
    val ink = if (danger) Am.HomeAccent else Am.HomeInk
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(7.dp)).deskPress(7.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = ink)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, Modifier.weight(1f), color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (hint != null) Text(hint, Modifier.padding(start = 12.dp), color = Am.HomeDim, fontSize = 12.sp, maxLines = 1)
        else if (selected) Box(Modifier.size(8.dp).background(Am.HomeAccent, CircleShape))
    }
}

@Composable
internal fun DeskMenuDivider() {
    Box(Modifier.padding(vertical = 4.dp, horizontal = 8.dp).fillMaxWidth().height(1.dp).background(Am.HomeLine))
}

internal fun Modifier.onSecondaryPress(onPress: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                val change = event.changes.first()
                onPress(change.position)
                change.consume()
            }
        }
    }
}

@Composable
internal fun <T> DeskDropdown(
    label: String, items: List<T>, shown: @Composable (T) -> String, selected: T?, onPick: (T) -> Unit, modifier: Modifier = Modifier,
    leading: (@Composable (T?) -> Unit)? = null, enabled: Boolean = true, menuWidth: Dp = 220.dp, fill: Color = Am.Card,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.alpha(if (enabled) 1f else 0.4f)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(fill).border(1.dp, Am.HomeLine, RoundedCornerShape(10.dp))
                .deskPress(10.dp).clickable(enabled = enabled) { open = true }.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let { it(selected); Spacer(Modifier.width(8.dp)) }
            Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(HomeIcons.ChevronDown, null, Modifier.size(20.dp), tint = Am.HomeMid)
        }
        if (open) DeskPopupMenu(BelowAnchor(false), { open = false }, menuWidth) {
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(7.dp)).deskPress(7.dp)
                        .clickable { open = false; onPick(item) }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.let { it(item); Spacer(Modifier.width(8.dp)) }
                    Text(shown(item), Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp, maxLines = 1, fontWeight = if (item == selected) FontWeight.Bold else FontWeight.Normal)
                    if (item == selected) Box(Modifier.size(8.dp).background(Am.HomeAccent, CircleShape))
                }
            }
        }
    }
}

@Composable
internal fun DeskSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).deskPress(8.dp)
            .clickable { onChange(!checked) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp)
        ThinSwitch(checked, enabled = true, offTrack = Am.SwitchOff, onTrack = Am.HomeAccent.copy(alpha = 0.5f), onThumb = Am.HomeAccent, onChange = onChange)
    }
}

@Composable
internal fun DeskMessage(text: String, onClose: () -> Unit) {
    DeskModal(onClose, 440.dp, onEnter = onClose) {
        Text(text, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)
        Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.End) {
            DeskButton(androidx.compose.ui.res.stringResource(app.absentmotion.R.string.ok), onClose, primary = true, minWidth = 92.dp)
        }
    }
}
