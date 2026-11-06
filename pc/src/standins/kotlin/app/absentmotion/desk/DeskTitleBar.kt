package app.absentmotion.desk

import android.content.mime
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.R
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.split
import app.absentmotion.platform.Hint
import app.absentmotion.platform.linkMediaFolder
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.TextIcons
import app.absentmotion.ui.home.BelowAnchor
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.home.SettingsPage
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.WeakHashMap
import javax.imageio.ImageIO
import kotlin.concurrent.thread

enum class WindowButton { Minimize, Maximize, Close }

object DeskWindow {
    const val BAR = 40
    private const val STRIP = 28f

    enum class Mode { Custom, MacStrip, System }

    data class Chrome(val start: Float = 0f, val end: Float = 0f, val strip: Float = 0f, val drawn: List<WindowButton> = emptyList(), val drawnLeft: Boolean = false)

    var chrome by mutableStateOf(Chrome())
    var maximized by mutableStateOf(false)
        private set
    internal var hit: Boolean? = null
    internal var bar: WindowDecorations.CustomTitleBar? = null
        private set
    private var window: Frame? = null
    private var height = BAR.toFloat()
    private var dark = true

    private val prefs get() = android.content.SharedPreferences.named("desk")
    var systemBarChosen: Boolean
        get() = prefs.getBoolean("systemTitleBar", false)
        set(value) { prefs.edit().putBoolean("systemTitleBar", value).apply() }

    internal fun mode(mac: Boolean, chosen: Boolean, decorations: Boolean): Mode = when {
        !chosen && decorations -> Mode.Custom
        mac && !chosen -> Mode.MacStrip
        else -> Mode.System
    }

    val icons: List<Image> by lazy {
        runCatching {
            val bytes = DeskWindow::class.java.getResourceAsStream("/AbsentMotion.ico")!!.use { it.readBytes() }
            val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            (0 until head.getShort(4).toInt()).mapNotNull { i -> ImageIO.read(ByteArrayInputStream(bytes, head.getInt(18 + 16 * i), head.getInt(14 + 16 * i))) }
        }.getOrDefault(emptyList())
    }

    private fun opened(window: Window, block: () -> Unit) {
        if (window.isShowing) block()
        window.addWindowListener(object : WindowAdapter() { override fun windowOpened(e: WindowEvent) = block() })
    }

    fun decorate(window: Window) = opened(window) { if (icons.isNotEmpty()) window.iconImages = icons }

    fun secondary(window: androidx.compose.ui.awt.ComposeWindow) {
        decorate(window)
        followTheme(window)
    }

    fun attach(window: androidx.compose.ui.awt.ComposeWindow) {
        this.window = window
        decorate(window)
        window.addWindowStateListener { maximized = it.newState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH }
        val decorations = runCatching { JBR.isWindowDecorationsSupported() }.getOrDefault(false)
        when (mode(DeskOs.mac, systemBarChosen, decorations)) {
            Mode.Custom -> {
                bar = runCatching { JBR.getWindowDecorations().createCustomTitleBar() }.getOrNull()
                if (DeskOs.mac) window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                if (DeskOs.linux) thread(isDaemon = true) { DeskOs.windowButtons }
                apply()
                opened(window) { apply() }
            }
            Mode.MacStrip -> {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                chrome = Chrome(strip = STRIP)
            }
            Mode.System -> followTheme(window)
        }
    }

    fun apply(height: Float = this.height, dark: Boolean = this.dark) {
        this.height = height
        this.dark = dark
        val bar = bar ?: return
        val window = window ?: return
        runCatching {
            bar.height = height
            if (DeskOs.windows) bar.putProperty("controls.dark", dark)
            JBR.getWindowDecorations().setCustomTitleBar(window, bar)
            val start = bar.leftInset
            val end = bar.rightInset
            val (buttons, left) = if (DeskOs.linux && start == 0f && end == 0f) DeskOs.windowButtons else emptyList<WindowButton>() to false
            chrome = Chrome(start, end, drawn = buttons, drawnLeft = left)
        }.onFailure {
            if (window.isDisplayable) {
                this.bar = null
                chrome = Chrome()
                runCatching { JBR.getWindowDecorations().setCustomTitleBar(window, null) }
            }
        }
    }

    fun press(button: WindowButton) {
        val window = window ?: return
        when (button) {
            WindowButton.Minimize -> window.extendedState = window.extendedState or Frame.ICONIFIED
            WindowButton.Maximize -> window.extendedState = if (maximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH
            WindowButton.Close -> window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        }
    }

    fun systemMenu() {
        val window = window as? androidx.compose.ui.awt.ComposeWindow ?: return
        if (DeskOs.windows && bar != null) runCatching { app.absentmotion.pc.engine.DesktopNative.systemMenu(window.windowHandle) }
    }

    internal fun place(saved: Rectangle?, screens: List<Rectangle>, primary: Rectangle, minWidth: Int, minHeight: Int): Rectangle {
        val screen = saved?.let { s -> screens.firstOrNull { it.contains(s.x + s.width / 2, s.y + BAR / 2) } }
        val area = screen ?: primary
        val width = (saved?.width ?: (area.width * 0.9).toInt()).coerceIn(minOf(minWidth, area.width), area.width)
        val height = (saved?.height ?: (area.height * 0.9).toInt()).coerceIn(minOf(minHeight, area.height), area.height)
        if (screen == null || saved == null) return Rectangle(area.x + (area.width - width) / 2, area.y + (area.height - height) / 2, width, height)
        return Rectangle(saved.x.coerceIn(area.x, area.x + area.width - width), saved.y.coerceIn(area.y, area.y + area.height - height), width, height)
    }

    fun screens(): Pair<List<Rectangle>, Rectangle> {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        fun usable(device: java.awt.GraphicsDevice): Rectangle {
            val config = device.defaultConfiguration
            val bounds = config.bounds
            val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(config) }.getOrNull() ?: return bounds
            return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
        }
        return environment.screenDevices.map(::usable) to usable(environment.defaultScreenDevice)
    }

    private val themed: MutableSet<androidx.compose.ui.awt.ComposeWindow> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    @Volatile private var systemDark: Boolean? = null

    @JvmStatic private external fun nativeSystemDark(): Boolean
    @JvmStatic private external fun nativeDarkTitleBar(handle: Long, dark: Boolean)

    private fun readDark(): Boolean? = runCatching { app.absentmotion.pc.engine.DesktopNative.hashCode(); nativeSystemDark() }.getOrNull()

    private fun paint(window: androidx.compose.ui.awt.ComposeWindow, dark: Boolean) { runCatching { nativeDarkTitleBar(window.windowHandle, dark) } }

    private fun followTheme(window: androidx.compose.ui.awt.ComposeWindow) {
        if (!DeskOs.windows) return
        themed += window
        opened(window) { readDark()?.let { paint(window, it) } }
        synchronized(this) {
            if (systemDark != null) return
            systemDark = readDark() ?: return
            thread(isDaemon = true, name = "am-theme") {
                while (true) {
                    Thread.sleep(2000)
                    val now = readDark() ?: continue
                    if (now == systemDark) continue
                    systemDark = now
                    javax.swing.SwingUtilities.invokeLater { synchronized(themed) { themed.toList() }.forEach { paint(it, now) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.dragArea(consume: Boolean): Modifier = pointerInput(consume) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (consume) event.changes.forEach { it.consume() }
            if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) continue
            DeskWindow.hit = false
            DeskWindow.bar?.let { bar -> runCatching { bar.forceHitTest(false) } }
            if (event.type == PointerEventType.Release && event.button == PointerButton.Secondary) DeskWindow.systemMenu()
        }
    }
}

private const val ButtonWidth = 36

@Composable
fun DeskWindowBar(app: DeskAppState, color: Color, content: @Composable RowScope.(menu: @Composable () -> Unit) -> Unit) {
    val scale = DeskScale.percent / 100f
    val chrome = DeskWindow.chrome
    val light = color.luminance() > 0.5f
    LaunchedEffect(scale, light) { DeskWindow.apply(DeskWindow.BAR * scale, !light) }
    val drawn = (ButtonWidth * chrome.drawn.size).dp
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().height(DeskWindow.BAR.dp).background(color)) {
            Box(Modifier.matchParentSize().dragArea(false))
            Row(
                Modifier.fillMaxSize().padding(start = (chrome.start / scale).dp + (if (chrome.drawnLeft) drawn else 0.dp) + 6.dp, end = (chrome.end / scale).dp + (if (chrome.drawnLeft) 0.dp else drawn) + 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content { DeskBurger(app, light) } }
            if (DeskModals.open > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = DeskModals.ScrimAlpha * DeskModals.fade)).dragArea(true))
            if (chrome.drawn.isNotEmpty()) Row(Modifier.align(if (chrome.drawnLeft) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 4.dp)) {
                chrome.drawn.forEach { WindowButtonView(it, light) }
            }
        }
    }
}

@Composable
private fun WindowButtonView(button: WindowButton, light: Boolean) {
    val (icon, label) = when (button) {
        WindowButton.Minimize -> WindowGlyphs.Minimize to stringResource(R.string.desk_minimize)
        WindowButton.Maximize -> if (DeskWindow.maximized) WindowGlyphs.Restore to stringResource(R.string.desk_restore) else WindowGlyphs.Maximize to stringResource(R.string.desk_maximize)
        WindowButton.Close -> ChromeIcons.Close to stringResource(R.string.action_close)
    }
    Hint(label) {
        Box(Modifier.width(ButtonWidth.dp).height(28.dp).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, if (light) Am.PressHome else Am.Field).handCursor().clickable { DeskWindow.press(button) }, Alignment.Center) {
            Icon(icon, label, Modifier.size(16.dp), tint = if (light) Am.Navy else Am.OnDark)
        }
    }
}

private object WindowGlyphs {
    val Minimize = glyph("Minimize", "M6 12L18 12")
    val Maximize = glyph("Maximize", "M6.5 6.5L17.5 6.5L17.5 17.5L6.5 17.5Z")
    val Restore = glyph("Restore", "M6 9L15 9L15 18L6 18ZM9 9L9 6L18 6L18 15L15 15")

    private fun glyph(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
}

data class MenuKey(val key: Key, val command: Boolean = true, val alt: Boolean = false)

class DeskMenuItem(val label: String, val enabled: Boolean = true, val keys: MenuKey? = null, val children: List<DeskMenuItem>? = null, val run: () -> Unit = {}) {
    val shortcut: KeyShortcut? get() = keys?.let { KeyShortcut(it.key, meta = it.command && DeskOs.mac, ctrl = it.command && !DeskOs.mac, alt = it.alt) }

    companion object { val Separator = DeskMenuItem("") }
}

fun deskMenus(app: DeskAppState, scope: kotlinx.coroutines.CoroutineScope): List<DeskMenuItem> {
    fun key(k: Key) = MenuKey(k)
    val editor = app.editor
    val chosen = editor?.chosenLayers?.isNotEmpty() == true
    val picked = editor?.selectedId != null || MultiSelect.active
    val linked = DeskLibrary.folders.also { DeskLibrary.revision.intValue }
    val separator = DeskMenuItem.Separator
    return listOf(
        DeskMenuItem("File", children = listOfNotNull(
            DeskMenuItem("New Project…", editor == null, key(Key.N)) { app.newProjectTick++ },
            DeskMenuItem("Settings…", keys = key(Key.Comma)) { app.openSettings() },
            separator,
            DeskMenuItem("Import Media…", editor != null) {
                val files = DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile)
                app.queueMedia(files)
            },
            DeskMenuItem("Import Project…", editor == null) {
                val files = DeskFiles.chooseFiles(media = false, multiple = true).map(Uri::fromFile)
                if (files.isNotEmpty()) scope.launch { app.importFiles(files) }
            },
            DeskMenuItem("Link Media Folder…") { linkMediaFolder() },
            if (linked.isEmpty()) null else DeskMenuItem("Unlink Folder", children = linked.map { folder -> DeskMenuItem(folder.name.ifEmpty { folder.path }) { DeskLibrary.unlink(folder) } }),
            separator,
            DeskMenuItem("Export…", editor != null, key(Key.E)) { editor?.let { it.playing = false; it.exportOpen = true } },
            DeskMenuItem("Close Project", editor != null, key(Key.W)) { app.closeSoon() },
        ) + if (DeskOs.mac) emptyList() else listOf(separator, DeskMenuItem("Quit", true, key(Key.Q)) { app.requestQuit() })),
        DeskMenuItem("Edit", children = listOf(
            DeskMenuItem("Undo", editor?.canUndo == true) { editor?.undo() },
            DeskMenuItem("Redo", editor?.canRedo == true) { editor?.redo() },
            separator,
            DeskMenuItem("Cut Layer", chosen) { editor?.let { it.copySelected(); if (MultiSelect.active) it.deleteChosen() else it.deleteSelected() } },
            DeskMenuItem("Copy Layer", chosen) { editor?.copySelected() },
            DeskMenuItem("Paste Layer", editor != null) { editor?.pasteLayer() },
            DeskMenuItem("Duplicate", picked) { editor?.let { if (MultiSelect.active) it.duplicateChosen() else it.duplicateSelected() } },
            DeskMenuItem("Split at Playhead", picked) { editor?.split() },
            DeskMenuItem("Delete", picked) { editor?.let { if (MultiSelect.active) it.deleteChosen() else it.deleteSelected() } },
        )),
        DeskMenuItem("View", children = listOf(
            DeskMenuItem("Zoom In", DeskScale.percent < DeskScale.MAX, key(Key.Equals)) { DeskScale.zoomIn() },
            DeskMenuItem("Zoom Out", DeskScale.percent > DeskScale.MIN, key(Key.Minus)) { DeskScale.zoomOut() },
            DeskMenuItem("Actual Size", DeskScale.percent != 100, key(Key.Zero)) { DeskScale.reset() },
        )),
        DeskMenuItem("Playback", children = listOf(
            DeskMenuItem(if (editor?.playing == true) "Pause" else "Play", editor != null) { editor?.let { it.playing = !it.playing } },
            DeskMenuItem("Go to Start", editor != null) { editor?.let { it.playing = false; it.frame = 0 } },
            DeskMenuItem("Go to End", editor != null) { editor?.let { it.playing = false; it.frame = it.scene.lastFrame } },
            separator,
            DeskMenuItem("Previous Bookmark", editor != null, MenuKey(Key.DirectionLeft, command = false, alt = true)) { editor?.let { DeskJump.previous(it) } },
            DeskMenuItem("Next Bookmark", editor != null, MenuKey(Key.DirectionRight, command = false, alt = true)) { editor?.let { DeskJump.next(it) } },
        )),
        DeskMenuItem("Help", children = listOf(
            DeskMenuItem("AbsentMotion Help") { app.openSettings(SettingsPage.Help) },
            DeskMenuItem("About AbsentMotion") { app.openSettings(SettingsPage.About) },
            DeskMenuItem("Crash Logs") { app.openSettings(SettingsPage.Crash) },
        )),
    )
}

internal fun shortcutText(shortcut: MenuKey, mac: Boolean): String {
    val key = when (shortcut.key) {
        Key.Comma -> ","
        Key.Equals -> "="
        Key.Minus -> "-"
        Key.DirectionLeft -> "←"
        Key.DirectionRight -> "→"
        else -> java.awt.event.KeyEvent.getKeyText(shortcut.key.nativeKeyCode)
    }
    return if (mac) (if (shortcut.alt) "⌥" else "") + (if (shortcut.command) "⌘" else "") + key
    else (if (shortcut.command) "Ctrl+" else "") + (if (shortcut.alt) "Alt+" else "") + key
}

@Composable
private fun DeskBurger(app: DeskAppState, light: Boolean) {
    var open by remember { mutableStateOf(false) }
    var closedAt by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.desk_menu)
    val button: @Composable () -> Unit = {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(if (open) (if (light) Am.PressHome else Am.Field) else Color.Transparent)
                .hoverFill(6.dp, if (light) Am.PressHome else Am.Field).handCursor().clickable { if (System.nanoTime() - closedAt > 300_000_000L) open = !open },
            Alignment.Center,
        ) { Icon(TextIcons.Menu, label, Modifier.size(20.dp), tint = if (light) Am.Navy else Am.OnDark) }
    }
    Box {
        if (open) button() else Hint(label, button)
        if (open) DeskMenuPopup(deskMenus(app, scope), light, { action -> scope.launch { delay(50); action() } }) { open = false; closedAt = System.nanoTime() }
    }
}

private const val RowHeight = 32
private const val SeparatorHeight = 9
private val ColumnWidths = intArrayOf(168, 280, 240)

private fun offsetOf(items: List<DeskMenuItem>, index: Int): Int = 4 + items.take(index).sumOf { if (it === DeskMenuItem.Separator) SeparatorHeight else RowHeight }

private fun step(items: List<DeskMenuItem>, from: Int, by: Int): Int {
    if (items.none { it !== DeskMenuItem.Separator }) return from
    var at = if (from < 0) (if (by > 0) -1 else items.size) else from
    do { at = (at + by).mod(items.size) } while (items[at] === DeskMenuItem.Separator)
    return at
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeskMenuPopup(menus: List<DeskMenuItem>, light: Boolean, run: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(listOf(-1)) }
    val focus = remember { FocusRequester() }
    DeskBack.Handler(true, onDismiss)
    fun levels(): List<List<DeskMenuItem>> {
        val out = mutableListOf(menus)
        for (i in 0 until path.size - 1) out += out.last().getOrNull(path[i])?.children ?: break
        return out
    }
    fun activate(level: Int, index: Int) {
        val item = levels()[level][index]
        when {
            item === DeskMenuItem.Separator || !item.enabled -> Unit
            item.children != null -> path = path.take(level) + index + -1
            else -> { onDismiss(); run(item.run) }
        }
    }
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val shown = levels()
        val level = path.lastIndex
        val items = shown.getOrNull(level) ?: return false
        val at = path[level]
        when (event.key) {
            Key.Escape -> onDismiss()
            Key.DirectionDown -> path = path.dropLast(1) + step(items, at, 1)
            Key.DirectionUp -> path = path.dropLast(1) + step(items, at, -1)
            Key.DirectionLeft -> if (path.size > 1) path = path.dropLast(1)
            Key.DirectionRight -> if (at >= 0 && items[at].children != null) activate(level, at)
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> if (at >= 0) activate(level, at)
            else -> return false
        }
        return true
    }
    val keys = rememberUpdatedState<(KeyEvent) -> Boolean> { key(it) }
    DisposableEffect(Unit) {
        val before = DeskKeys.viewer
        DeskKeys.viewer = { keys.value(it) }
        onDispose { DeskKeys.viewer = before }
    }
    val surface = if (light) Am.Card else Am.Field
    val edge = if (light) Am.HomeLine else Am.Line
    val ink = if (light) Am.HomeInk else Am.OnDark
    val dim = if (light) Am.HomeDim else Am.OnDarkDim
    val hover = if (light) Am.HomePanel else Am.FieldHi
    Popup(BelowAnchor(false), onDismiss, PopupProperties(focusable = true), onPreviewKeyEvent = ::key) {
        val shown = levels()
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(Modifier.focusRequester(focus).focusable()) {
            var top = 0
            shown.forEachIndexed { level, items ->
                if (level > 0) top += offsetOf(shown[level - 1], path[level - 1]) - 4
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.padding(top = top.dp, start = if (level > 0) 2.dp else 0.dp).width(ColumnWidths[level.coerceAtMost(ColumnWidths.lastIndex)].dp)
                        .shadow(12.dp, shape).clip(shape).background(surface).border(1.dp, edge, shape).padding(vertical = 4.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        if (item === DeskMenuItem.Separator) Box(Modifier.fillMaxWidth().height(SeparatorHeight.dp).padding(vertical = 4.dp, horizontal = 8.dp).background(edge))
                        else Row(
                            Modifier.fillMaxWidth().height(RowHeight.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (path.getOrNull(level) == index) hover else Color.Transparent)
                                .onPointerEvent(PointerEventType.Enter) { path = if (item.children != null && item.enabled) path.take(level) + index + -1 else path.take(level) + index }
                                .then(if (item.enabled) Modifier.handCursor().clickable { activate(level, index) } else Modifier)
                                .padding(horizontal = 10.dp),
                            Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                        ) {
                            Text(item.label, Modifier.weight(1f), color = if (item.enabled) ink else dim.copy(alpha = .6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (level == 0) FontWeight.Medium else FontWeight.Normal)
                            item.keys?.let { Text(shortcutText(it, DeskOs.mac), color = dim.copy(alpha = if (item.enabled) 1f else .6f), fontSize = 12.sp, maxLines = 1) }
                            if (item.children != null) Icon(ChromeIcons.ChevronRight, null, Modifier.size(16.dp), tint = if (item.enabled) dim else dim.copy(alpha = .6f))
                        }
                    }
                }
            }
        }
    }
}
