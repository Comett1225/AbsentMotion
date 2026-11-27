package app.absentmotion.desk

import app.absentmotion.i18n.AppText
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.platform.Hint
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.home.SettingsPage
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
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
            ) { content { DeskBurger(light) { scope -> deskMenus(app, scope) } } }
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

private class DeskAppMenus(private val app: DeskAppState, private val scope: kotlinx.coroutines.CoroutineScope) : DeskMenuHost {
    override val context get() = app.context
    override val editor get() = app.editor
    override val canStartProject get() = app.canStartProject
    override val canImportLink get() = app.canStartProject && !app.opening && !app.importLinkOpen
    override val linkedFolders get() = DeskLibrary.folders.also { DeskLibrary.revision.intValue }.map { folder -> folder.name.ifEmpty { folder.path } to { DeskLibrary.unlink(folder) } }
    override val view get() = listOf(
        DeskMenuItem(AppText.get(R.string.desk_menu_zoom_in), DeskScale.percent < DeskScale.MAX, menuKey(Shortcut.ZoomIn), action = Shortcut.ZoomIn) { DeskScale.zoomIn() },
        DeskMenuItem(AppText.get(R.string.desk_menu_zoom_out), DeskScale.percent > DeskScale.MIN, menuKey(Shortcut.ZoomOut), action = Shortcut.ZoomOut) { DeskScale.zoomOut() },
        DeskMenuItem(AppText.get(R.string.desk_menu_actual_size), DeskScale.percent != 100, menuKey(Shortcut.ActualSize), action = Shortcut.ActualSize) { DeskScale.reset() },
    )
    override val quit get() = if (DeskOs.mac) null else app::requestQuit
    override fun newProject() { app.newProjectTick++ }
    override fun openSettings() = app.openSettings()
    override fun importMedia() = app.queueMedia(DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile))
    override fun importProjects() {
        val files = DeskFiles.chooseFiles(media = false, multiple = true).map(Uri::fromFile)
        if (files.isNotEmpty()) scope.launch { app.importFiles(files) }
    }
    override fun importLink() { app.importLinkOpen = true }
    override fun closeProject() = app.closeSoon()
    override fun openHelp() = app.openSettings(SettingsPage.Help)
    override fun openAbout() = app.openSettings(SettingsPage.About)
    override fun openCrashLogs() = app.openSettings(SettingsPage.Crash)
}

fun deskMenus(app: DeskAppState, scope: kotlinx.coroutines.CoroutineScope): List<DeskMenuItem> = deskMenus(DeskAppMenus(app, scope))
