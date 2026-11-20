package app.absentmotion.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import app.absentmotion.ui.adaptive.deskLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.adaptive.AdaptiveSpace
import app.absentmotion.ui.adaptive.LocalAdaptiveSpace
import app.absentmotion.ui.adaptive.adaptiveWindowSize
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.theme.HoverTargets
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.withUiFont

@Composable
internal fun Modifier.deskCursor(): Modifier =
    if (deskLayout) pointerHoverIcon(if (HoverTargets.count > 0) PointerIcon.Hand else PointerIcon.Default) else this

@Composable
internal fun DeskCursorHost(modifier: Modifier, alignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.deskCursor(), alignment, content = content)
}

@Composable
internal fun SizedBox(modifier: Modifier = Modifier, alignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.(maxWidth: Dp, maxHeight: Dp) -> Unit) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.onSizeChanged { size = it }, alignment) {
        if (size.width > 0) content(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
    }
}

@Composable
private fun modalFade(): Float {
    DisposableEffect(Unit) {
        DeskModals.open++
        onDispose { DeskModals.open--; if (DeskModals.open == 0) DeskModals.fade = 0f }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, Motion.fast(), label = "scrim")
    SideEffect { DeskModals.fade = fade }
    return fade
}

@Composable
internal fun DeskDialog(title: String?, width: Dp? = null, onDismiss: () -> Unit, height: Dp? = null, onConfirm: (() -> Unit)? = null, outside: Boolean = true, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    fun close() { focus.clearFocus(); onDismiss() }
    BackHandler { close() }
    DisposableEffect(Unit) { onDispose { focus.clearFocus() } }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val fade = modalFade()
    DeskCursorHost(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DeskModals.ScrimAlpha * fade)).pointerInput(outside) { detectTapGestures { if (outside) close() } }.padding(24.dp), Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val card = if (width == null) Modifier.size(adaptiveWindowSize(maxWidth, maxHeight))
                else Modifier.widthIn(max = width).fillMaxWidth().let { if (height == Dp.Infinity) it.fillMaxHeight() else if (height != null) it.height(height) else it.heightIn(max = 720.dp) }
            Column(card.shadow(24.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Am.Panel).pointerInput(Unit) { detectTapGestures { } }
                .onKeyEvent {
                    if (onConfirm != null && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { focus.clearFocus(); onConfirm(); true } else false
                }.focusRequester(requester).focusTarget()) {
                if (title != null) Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DeskIcon(ChromeIcons.Close, deskShortcut(R.string.action_close, "Esc")) { close() }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = width == null || height != null || title == null)) {
                    CompositionLocalProvider(LocalWide provides false, LocalAdaptiveSpace provides if (width == null || width == Dp.Infinity) AdaptiveSpace(maxWidth, maxHeight) else null) { content() }
                }
            }
        }
    }
}

internal val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

internal val DeskMod = if (mac) "⌘" else "Ctrl+"
internal val DeskAlt = if (mac) "⌥" else "Alt+"
internal val DeskShift = if (mac) "⇧" else "Shift+"
internal val DeskRedoKeys = if (mac) "⇧⌘Z" else "Ctrl+Shift+Z"

internal fun deskSp(phone: Float): TextUnit = if (deskLayout && phone < 12f) 12.sp else phone.sp

@Composable
internal fun deskShortcut(label: Int, keys: String): String = if (keys.isEmpty()) stringResource(label) else stringResource(R.string.desk_shortcut_hint, stringResource(label), keys)

internal fun deskKeys(action: String, fallback: String): String = DeskDrop.keyNames[action] ?: fallback

@Composable
internal fun DeskIcon(icon: ImageVector, hint: String, enabled: Boolean = true, active: Boolean = false, size: Dp = 36.dp, iconSize: DpSize = DpSize(size - 14.dp, size - 14.dp), shortcut: app.absentmotion.desk.Shortcut? = null, action: () -> Unit) {
    val button = @Composable { BarButton(icon, hint, Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(if (active) Am.Field else Color.Transparent), enabled = enabled, iconSize = iconSize, hint = hint, onClick = action) }
    if (shortcut == null) button() else ShortcutMenuBox(shortcut) { button() }
}

@Composable
internal fun DeskSheetHost(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    fun close() { focus.clearFocus(); onDismiss() }
    BackHandler { close() }
    DisposableEffect(Unit) { onDispose { focus.clearFocus() } }
    val fade = modalFade()
    DeskCursorHost(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DeskModals.ScrimAlpha * fade)).pointerInput(Unit) { detectTapGestures { close() } }.padding(24.dp), Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), Alignment.Center) {
            val size = adaptiveWindowSize(minOf(maxWidth, 720.dp), maxHeight)
            Box(Modifier.size(size).clip(RoundedCornerShape(12.dp))) {
                CompositionLocalProvider(LocalWide provides false, LocalAdaptiveSpace provides AdaptiveSpace(size.width, size.height)) { content() }
            }
        }
    }
}

@Composable
internal fun DeskTip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (deskLayout && text.isNotEmpty()) app.absentmotion.platform.Hint(text, modifier, content) else content()
}

@Composable
internal fun DeskNameField(value: String, modifier: Modifier = Modifier, state: app.absentmotion.editor.EditorState? = null, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var shown by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        val name = typedName(text, shown)
        text = name ?: shown
        if (name != null) { shown = name; onCommit(name) }
    }
    val commitNow by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { commitNow() } }
    BasicTextField(
        text, { text = it; state?.typing = commitNow },
        modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(if (focused) Am.Field else Color.Transparent).hoverFill(6.dp, Am.Field).pointerHoverIcon(PointerIcon.Text)
            .onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { text = value; focus.clearFocus(); true }
                else if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { focus.clearFocus(); true } else false
            },
        textStyle = TextStyle(color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium).withUiFont(),
        singleLine = true, cursorBrush = SolidColor(Am.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), Alignment.CenterStart) { inner() } },
    )
}

@Composable
internal fun DeskParentButton(onClick: () -> Unit) {
    val label = stringResource(R.string.structure_link_parent)
    app.absentmotion.platform.Hint(label) {
        Box(Modifier.size(40.dp, 42.dp).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, Am.Field).handCursor().semantics { contentDescription = label }.clickable(onClick = onClick), Alignment.Center) {
            LayerParentIcon(back = Am.Panel)
        }
    }
}

internal fun Modifier.rightClick(action: () -> Unit): Modifier = if (!deskLayout) this else pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && (event.buttons.isSecondaryPressed || mac && event.keyboardModifiers.isCtrlPressed)) {
                action()
                event.changes.forEach { it.consume() }
            }
        }
    }
}
