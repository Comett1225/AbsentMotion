package app.absentmotion.ui.home

import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import app.absentmotion.platform.PlatformFeatures
import androidx.compose.material3.AlertDialog
import app.absentmotion.library.SavedSize
import app.absentmotion.library.SavedSizes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.absentmotion.library.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.absentmotion.library.NewProjectChoice
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.adaptive.AdaptiveWindow
import app.absentmotion.ui.adaptive.LocalAdaptiveSpace
import app.absentmotion.ui.adaptive.scrimTap
import app.absentmotion.ui.theme.LocalPresence
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.presenceScrim
import app.absentmotion.ui.theme.withUiFont
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.editor.panels.DoubleTapToType
import app.absentmotion.ui.HoldName

private val Ink get() = Am.HomeInk
private val DimText get() = Am.HomeDim
private val ListColor get() = Am.Navy
private val ListLine get() = Am.NavyLine
private val FieldShape = RoundedCornerShape(10.dp)
private val SheetWidth = 384.dp
internal const val MIN_SIDE = 32
internal const val MAX_SIDE = 4096

internal val Resolutions = listOf(
    2160 to "2160p (4k)", 1440 to "1440p (QHD)", 1080 to "1080p (FHD)", 720 to "720p (HD)",
    540 to "540p (SD)", 360 to "360p", 270 to "270p", 180 to "180p",
)
internal val FrameRates = listOf(12, 15, 18, 20, 24, 25, 30, 48, 50, 60, 90, 120)

internal class BackgroundChoice(@StringRes val name: Int, val argb: Int)

internal val Backgrounds = listOf(
    BackgroundChoice(R.string.newproject_bg_black, 0xFF000000.toInt()), BackgroundChoice(R.string.newproject_bg_white, 0xFFFFFFFF.toInt()),
    BackgroundChoice(R.string.newproject_bg_light_grey, 0xFFD8D8D8.toInt()), BackgroundChoice(R.string.newproject_bg_green, 0xFF00B140.toInt()),
    BackgroundChoice(R.string.newproject_bg_blue, 0xFF0047BB.toInt()), BackgroundChoice(R.string.newproject_bg_transparent, 0x00000000),
)

internal class AspectDef(val key: String, val w: Int, val h: Int, val boxW: Dp, val boxH: Dp)

internal val Aspects = listOf(
    AspectDef("16:9", 16, 9, 55.dp, 31.dp), AspectDef("9:16", 9, 16, 31.dp, 55.dp),
    AspectDef("4:5", 4, 5, 31.dp, 39.dp), AspectDef("1:1", 1, 1, 31.dp, 31.dp),
    AspectDef("4:3", 4, 3, 41.dp, 31.dp),
)

internal const val CUSTOM = "custom"

internal class Form(saved: NewProjectChoice, val titles: List<String>, val names: DefaultNames, initialElement: Boolean) {
    var element by mutableStateOf(initialElement)
    var name by mutableStateOf(names.of(initialElement, titles))
    var aspect by mutableStateOf(saved.aspect)
    var resolution by mutableIntStateOf(saved.resolution)
    var customW by mutableStateOf(saved.customWidth.toString())
    var customH by mutableStateOf(saved.customHeight.toString())
    var linked by mutableStateOf(false)
    var fps by mutableIntStateOf(saved.fps)
    var background by mutableIntStateOf(saved.background)
    var picked by mutableStateOf<SavedSize?>(null)
    private var ratio = 1f
    private var fit by mutableStateOf<Pair<Int, Int>?>(null)

    val size: Pair<Int, Int>
        get() {
            if (aspect == CUSTOM) return fit ?: (side(customW) to side(customH))
            val (a, b) = Aspects.firstOrNull { it.key == aspect }?.let { it.w to it.h } ?: (1 to 1)
            val long = ((resolution * maxOf(a, b) / minOf(a, b).toDouble()) / 2).roundToInt() * 2
            return if (a >= b) long to resolution else resolution to long
        }

    fun switchType(on: Boolean) {
        if (on == element) return
        if (name == names.of(element, titles)) name = names.of(on, titles)
        element = on
    }

    fun editWidth(text: String) {
        customW = text.filter(Char::isDigit).take(4)
        fit = if (linked) customW.toIntOrNull()?.let { linkedSides(it.toFloat(), it / ratio) } else null
        fit?.let { customH = it.second.toString() }
    }

    fun editHeight(text: String) {
        customH = text.filter(Char::isDigit).take(4)
        fit = if (linked) customH.toIntOrNull()?.let { linkedSides(it * ratio, it.toFloat()) } else null
        fit?.let { customW = it.first.toString() }
    }

    fun pick(w: Int, h: Int) {
        fit = null
        customW = w.toString()
        customH = h.toString()
        if (linked) ratio = w.toFloat() / h
    }

    fun pick(size: SavedSize) {
        aspect = CUSTOM
        pick(size.width, size.height)
        picked = size
    }

    fun shows(size: SavedSize) = aspect == CUSTOM && picked == size && this.size == size.width to size.height

    fun toggleLink() {
        linked = !linked
        fit = null
        if (linked) ratio = side(customW).toFloat() / side(customH)
    }

    fun commitSides() {
        fit?.let { (w, h) -> customW = w.toString(); customH = h.toString() }
        fit = null
        customW = side(customW).toString()
        customH = side(customH).toString()
    }

    fun choice() = (fit ?: (side(customW) to side(customH))).let { (w, h) -> NewProjectChoice(aspect, resolution, w, h, fps, background) }

    fun scene(): Scene {
        val (w, h) = size
        val title = name.trim().ifEmpty { names.of(element, titles) }
        val scene = Scene.create(title, w, h, fps, if (element) 0 else background)
        return if (element) Scene(scene.node.with("type", "element")) else scene
    }
}

internal fun side(text: String) = text.toIntOrNull()?.coerceIn(MIN_SIDE, MAX_SIDE) ?: MIN_SIDE

internal fun linkedSides(w: Float, h: Float): Pair<Int, Int> {
    val k = if (w > 0f && h > 0f) minOf(MAX_SIDE / maxOf(w, h), maxOf(1f, MIN_SIDE / minOf(w, h))) else 1f
    return (w * k).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE) to (h * k).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE)
}

internal fun tileFrame(width: Int, height: Int): Pair<Float, Float> {
    val ratio = maxOf(width, height).toFloat() / minOf(width, height).coerceAtLeast(1)
    val long = minOf(TILE_SHORT * ratio, TILE_LONG)
    val short = (long / ratio).coerceAtLeast(TILE_THIN)
    return if (width >= height) long to short else short to long
}

private const val TILE_SHORT = 31f
private const val TILE_LONG = 55f
private const val TILE_THIN = 5f

internal class MarqueeFrame(val offset: Float, val alpha: Float)

internal object Marquee {
    const val WAIT_MS = 1000L
    const val HOLD_MS = 800L
    const val FADE_MS = 300L

    fun travelMs(overflow: Float, speed: Float): Long = (overflow / speed * 1000f).toLong().coerceAtLeast(1L)

    fun frame(ms: Long, overflow: Float, speed: Float): MarqueeFrame {
        if (overflow <= 0f || speed <= 0f) return MarqueeFrame(0f, 1f)
        val travel = travelMs(overflow, speed)
        val t = ms.coerceAtLeast(0L) % (WAIT_MS + travel + HOLD_MS + 2 * FADE_MS)
        val moved = t - WAIT_MS
        val held = moved - travel
        val faded = held - HOLD_MS
        return when {
            moved < 0 -> MarqueeFrame(0f, 1f)
            held < 0 -> MarqueeFrame(overflow * moved / travel, 1f)
            faded < 0 -> MarqueeFrame(overflow, 1f)
            faded < FADE_MS -> MarqueeFrame(overflow, 1f - faded.toFloat() / FADE_MS)
            else -> MarqueeFrame(0f, (faded - FADE_MS).toFloat() / FADE_MS)
        }
    }
}

internal class DefaultNames(private val context: Context) {
    private val numbered = context.getString(R.string.newproject_default_project, "\u0000").split('\u0000', limit = 2)
    private val pattern = Regex(Regex.escape(numbered[0]) + """(\d+)""" + Regex.escape(numbered.getOrElse(1) { "" }))

    fun of(element: Boolean, titles: List<String>): String {
        if (element) return context.getString(R.string.newproject_default_asset)
        val highest = titles.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
        return context.getString(R.string.newproject_default_project, ((highest ?: titles.size) + 1).toString())
    }
}

@Composable
fun NewProjectSheet(
    store: ProjectStore,
    settings: AppSettings,
    existingTitles: List<String>,
    initialElement: Boolean,
    opening: Int,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val form = remember(opening) { Form(settings.newProject, existingTitles, DefaultNames(context), initialElement) }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val card = app.absentmotion.ui.editor.LocalTablet.current
    val create: () -> Unit = create@{
        if (creating) return@create
        creating = true
        settings.newProject = form.choice()
        val scene = form.scene()
        scope.launch {
            val id = try {
                withContext(Dispatchers.IO) { store.create(scene) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                creating = false
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.home_create_project_failed), Toast.LENGTH_LONG).show()
                creating = false
                return@launch
            }
            onClose()
            onOpen(id)
        }
    }
    val presence = LocalPresence.current
    val dismiss = { if (presence?.leaving != true) onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (card && maxWidth > maxHeight && maxWidth - 48.dp >= 720.dp) {
            AdaptiveWindow(1100.dp, 500.dp, onDismiss = dismiss) { SheetBody(form, settings, true, onClose, create) }
        } else {
            Box(Modifier.fillMaxSize().presenceScrim(Color.Black.copy(alpha = 0.7f)).scrimTap(dismiss))
            BoxWithConstraints(
                if (card) Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(24.dp).widthIn(max = 480.dp).presence(scaleFrom = 0.96f)
                else Modifier.align(Alignment.BottomCenter).widthIn(max = 480.dp).presence(slide = 1f, fade = false),
            ) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * minOf(1f, maxWidth / SheetWidth), density.fontScale)) {
                    SheetBody(form, settings, card, onClose, create)
                }
            }
        }
    }
}

@Composable
private fun SheetBody(form: Form, settings: AppSettings, card: Boolean, onClose: () -> Unit, onCreate: () -> Unit) {
    Column(
        if (card) (if (LocalAdaptiveSpace.current?.wide == true) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).clip(RoundedCornerShape(22.dp)).background(Am.SheetBg).verticalScroll(rememberScrollState())
        else Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Am.SheetBg)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).verticalScroll(rememberScrollState()),
    ) {
        if (card) Row(Modifier.padding(start = 20.dp, top = 12.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(HomeIcons.Close, stringResource(R.string.cancel), Modifier.size(22.dp), tint = Am.Navy)
                HoldName(stringResource(R.string.cancel))
            }
            Text(stringResource(R.string.home_new_project), Modifier.padding(start = 4.dp), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        var savedPages by remember { mutableStateOf(emptyList<List<SavedSize>>()) }
        val pages = if (PlatformFeatures.desktop) null else rememberPagerState { 1 + savedPages.size }
        val identity: @Composable ColumnScope.() -> Unit = {
            Spacer(Modifier.height(if (card) 12.dp else 30.dp))
            TypeToggle(form)
            Spacer(Modifier.height(35.dp))
            NameField(form)
            Spacer(Modifier.height(10.dp))
            if (pages == null) AspectRow(form) else BoxWithConstraints(Modifier.fillMaxWidth()) {
                val chunks = savedPagesFor(settings.savedSizes, maxWidth)
                androidx.compose.runtime.SideEffect { if (savedPages != chunks) savedPages = chunks }
                HorizontalPager(pages, Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.Top, flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(pages, snapPositionalThreshold = 0.25f)) { page ->
                    if (page == 0) AspectRow(form) else SavedPage(form, settings, chunks.getOrElse(page - 1) { emptyList() })
                }
            }
        Row(Modifier.fillMaxWidth().height(16.dp), Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
            if (pages != null && pages.pageCount > 1) repeat(pages.pageCount) { Box(Modifier.size(4.dp).background(if (pages.currentPage == it) Am.Navy else Am.HomeFaint, CircleShape)) }
        }
        }
        val dimensions: @Composable ColumnScope.() -> Unit = {
            SizeRow(form)
            AnimatedVisibility(form.aspect == CUSTOM, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val scope = rememberCoroutineScope()
                SavedSizeStrip(form, settings, chips = pages == null) {
                    if (pages != null) scope.launch {
                        androidx.compose.runtime.snapshotFlow { pages.pageCount }.first { it > 1 }
                        pages.animateScrollToPage(1)
                    }
                }
        }
        Spacer(Modifier.height(16.dp))
        FormRow(stringResource(R.string.newproject_frame_rate)) { FrameRateField(form) }
        Spacer(Modifier.height(16.dp))
        FormRow(stringResource(R.string.newproject_background)) { BackgroundField(form) }
        Spacer(Modifier.height(16.dp))
        }
        if (LocalAdaptiveSpace.current?.wide == true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), content = identity)
                Column(Modifier.weight(1f), content = dimensions)
            }
        } else {
            identity()
            dimensions()
        }
        if (card) {
            Spacer(Modifier.height(12.dp))
            AccentPill(stringResource(if (form.element) R.string.newproject_create_asset else R.string.newproject_create_project), Modifier.padding(horizontal = 32.dp).fillMaxWidth(), onClick = onCreate)
            Spacer(Modifier.height(32.dp))
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        AccentPill(stringResource(if (form.element) R.string.newproject_create_asset else R.string.newproject_create_project), Modifier.padding(horizontal = 32.dp).fillMaxWidth(), onClick = onCreate)
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(60.dp).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.Close, stringResource(R.string.cancel), Modifier.size(24.dp), tint = Am.Navy)
            HoldName(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun TypeToggle(form: Form) {
    val shift by androidx.compose.animation.core.animateFloatAsState(if (form.element) 1f else 0f, androidx.compose.animation.core.tween(220), label = "type")
    BoxWithConstraints(Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(44.dp).background(Am.Card, FieldShape).padding(3.dp)) {
        val gap = 6.dp
        val half = (maxWidth - gap) / 2
        Box(Modifier.offset(x = (half + gap) * shift).width(half).fillMaxHeight().shadow(3.dp, FieldShape).background(Am.Navy, FieldShape))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Segment(stringResource(R.string.newproject_segment_project), 1f - shift, Modifier.weight(1f)) { form.switchType(false) }
            Segment(stringResource(R.string.newproject_segment_asset), shift, Modifier.weight(1f)) { form.switchType(true) }
        }
    }
}

@Composable
private fun Segment(text: String, selected: Float, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clip(FieldShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = androidx.compose.ui.graphics.lerp(Am.Navy, Am.OnNavyAccent, selected), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NameField(form: Form) {
    Box(Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(40.dp)) {
        BasicTextField(
            form.name, { form.name = it },
            Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(start = 4.dp, end = 40.dp),
            textStyle = TextStyle(color = Ink, fontSize = 16.sp).withUiFont(), singleLine = true, cursorBrush = SolidColor(Am.Navy),
        )
        Box(Modifier.align(Alignment.TopStart).offset(y = 31.dp).padding(horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Am.HomeMid))
        Box(
            Modifier.align(Alignment.CenterEnd).offset(x = 12.dp).size(48.dp).clickable { form.name = "" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.ClearCircle, stringResource(R.string.newproject_clear_title), Modifier.size(24.dp), tint = Ink)
            HoldName(stringResource(R.string.newproject_clear_title))
        }
    }
}

@Composable
private fun AspectRow(form: Form) {
    Row(Modifier.padding(start = 27.dp).height(56.dp)) {
        Aspects.forEach { a ->
            AspectButton(a.boxW, a.boxH, form.aspect == a.key, onClick = { form.aspect = a.key }) {
                Text(a.key, Modifier.wrapContentWidth(unbounded = true), color = if (form.aspect == a.key) Am.OnAccent else Am.Navy, fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
        }
        val custom = form.aspect == CUSTOM
        AspectButton(31.dp, 31.dp, custom, stringResource(R.string.newproject_custom_aspect), onClick = { form.aspect = CUSTOM }) {
            Icon(HomeIcons.Pencil, stringResource(R.string.newproject_custom_aspect), Modifier.size(16.dp), tint = if (custom) Am.OnAccent else Am.HomeDim)
        }
    }
}

@Composable
private fun AspectButton(w: Dp, h: Dp, selected: Boolean, name: String = "", onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.height(56.dp).clickable(onClick = onClick).padding(horizontal = 9.25.dp), contentAlignment = Alignment.Center) {
        HoldName(name)
        Box(Modifier.size(w, h).frameLook(selected), contentAlignment = Alignment.Center, content = content)
    }
}

private fun Modifier.frameLook(selected: Boolean): Modifier = if (selected) {
    val shape = RoundedCornerShape(2.dp)
    background(Am.Accent, shape).border(1.dp, Am.Navy, shape)
} else {
    background(Am.Card).corners(Am.Navy)
}

@Composable
private fun savedPagesFor(sizes: List<SavedSize>, width: Dp): List<List<SavedSize>> {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sizes, width, density) {
        val room = width - 45.dp
        val name = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val label = TextStyle(fontSize = 11.sp)
        val pages = mutableListOf<MutableList<SavedSize>>()
        var used = 0.dp
        sizes.forEach { size ->
            val text = with(density) { maxOf(measurer.measure(size.name, name).size.width, measurer.measure(size.label, label).size.width).toDp() }
            val tile = 24.5.dp + tileFrame(size.width, size.height).first.dp + 8.dp + minOf(text, 104.dp)
            if (pages.isEmpty() || used + tile > room) {
                pages += mutableListOf(size)
                used = tile
            } else {
                pages.last() += size
                used += tile
            }
        }
        pages
    }
}

@Composable
private fun SavedPage(form: Form, settings: AppSettings, sizes: List<SavedSize>) {
    var renaming by remember { mutableStateOf<SavedSize?>(null) }
    Row(Modifier.fillMaxSize().padding(start = 27.dp, end = 18.dp)) {
        sizes.forEach { size ->
            SavedTile(size, form.shows(size), settings.vibration, { form.pick(size) }, { renaming = size }) {
                settings.savedSizes = SavedSizes.without(settings.savedSizes, size)
            }
        }
    }
    renaming?.let { target ->
        SizeNameDialog(stringResource(R.string.rename), target.name, dark = false, onDismiss = { renaming = null }) { name ->
            renaming = null
            val list = SavedSizes.renamed(settings.savedSizes, target, name)
            if (form.picked == target) form.picked = list.getOrNull(settings.savedSizes.indexOf(target))
            settings.savedSizes = list
        }
    }
}

@Composable
private fun SavedTile(size: SavedSize, selected: Boolean, vibrate: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pick = remember { HoldPick() }
    val rename = stringResource(R.string.rename)
    val delete = stringResource(R.string.delete)
    val (w, h) = tileFrame(size.width, size.height)
    Box {
        Row(
            Modifier.height(56.dp)
                .clearAndSetSemantics {
                    contentDescription = size.name + ", " + size.label
                    role = Role.Button
                    this.selected = selected
                    onClick { onClick(); true }
                    customActions = listOf(CustomAccessibilityAction(rename) { onRename(); true }, CustomAccessibilityAction(delete) { onDelete(); true })
                }
                .clickable(onClick = onClick).holdToPick(pick, vibrate) { menu = true }.padding(horizontal = 9.25.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(w.dp, h.dp).frameLook(selected))
            Column(Modifier.padding(start = 8.dp).widthIn(max = 104.dp)) {
                MarqueeText(size.name, Am.Navy)
                Text(size.label, color = DimText, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false)
            }
        }
        RefMenu(menu, { menu = false }, Am.Card, RoundedCornerShape(14.dp), Modifier.width(200.dp)) {
            MenuRow(rename, HomeIcons.Pencil, { menu = false; onRename() }, pick)
            MenuRow(delete, HomeIcons.Delete, { menu = false; onDelete() }, pick)
        }
    }
}

@Composable
private fun MarqueeText(text: String, color: Color) {
    var overflow by remember(text) { mutableFloatStateOf(0f) }
    var ms by remember(text) { mutableLongStateOf(0L) }
    val speed = with(LocalDensity.current) { 28.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (overflow > 0f) LaunchedEffect(text) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { ms = it - start }
    }
    Layout(
        {
            Text(
                text,
                Modifier.graphicsLayer {
                    val frame = Marquee.frame(ms, overflow, speed)
                    translationX = if (rtl) frame.offset else -frame.offset
                    alpha = frame.alpha
                },
                color = color, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
            )
        },
        Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val width = minOf(placeable.width, constraints.maxWidth)
        overflow = (placeable.width - width).toFloat()
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

private fun Modifier.corners(color: Color): Modifier = drawBehind {
    val arm = 5.dp.toPx()
    val t = 1.3.dp.toPx()
    val half = t / 2
    for (left in listOf(true, false)) {
        for (top in listOf(true, false)) {
            val x = if (left) half else size.width - half
            val y = if (top) half else size.height - half
            val dx = if (left) 1f else -1f
            val dy = if (top) 1f else -1f
            drawLine(color, Offset(x - dx * half, y), Offset(x + dx * arm, y), t)
            drawLine(color, Offset(x, y - dy * half), Offset(x, y + dy * arm), t)
        }
    }
}

@Composable
private fun FormRow(label: String, details: List<String> = emptyList(), field: @Composable () -> Unit) {
    val wide = LocalAdaptiveSpace.current?.wide == true
    Row(Modifier.padding(horizontal = 32.dp).fillMaxWidth().heightIn(min = 45.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            (if (wide) Modifier.weight(0.44f) else Modifier.width(140.dp)).align(if (details.isEmpty()) Alignment.CenterVertically else Alignment.Top)
                .wrapContentHeight(Alignment.Top, unbounded = true),
        ) {
            Text(label, color = Am.Navy, fontSize = 16.sp, lineHeight = 22.sp)
            details.forEach { Text(it, color = DimText, fontSize = 10.sp, lineHeight = 13.sp) }
        }
        Box((if (wide) Modifier.weight(0.56f) else Modifier.width(180.dp)).height(45.dp)) { field() }
    }
}

@Composable
private fun SizeRow(form: Form) {
    val (w, h) = form.size
    FormRow(stringResource(R.string.newproject_resolution), listOf(stringResource(R.string.newproject_canvas_size), "$w ⨉ $h")) {
        if (form.aspect == CUSTOM) CustomSize(form) else ResolutionField(form)
    }
}

@Composable
private fun ResolutionField(form: Form) {
    DropdownField(Resolutions.firstOrNull { it.first == form.resolution }?.second ?: "${form.resolution}p") { close ->
        Resolutions.forEachIndexed { i, (value, label) ->
            PickRow(label, divider = i < Resolutions.lastIndex) { form.resolution = value; close() }
        }
    }
}

@Composable
private fun FrameRateField(form: Form) {
    DropdownField("${form.fps} fps", selected = FrameRates.indexOf(form.fps)) { close ->
        FrameRates.forEachIndexed { i, rate ->
            PickRow("$rate fps", divider = i < FrameRates.lastIndex) { form.fps = rate; close() }
        }
    }
}

@Composable
private fun BackgroundField(form: Form) {
    val shown = if (form.element) Backgrounds.last() else Backgrounds.firstOrNull { it.argb == form.background } ?: Backgrounds.last()
    DropdownField(stringResource(shown.name), enabled = !form.element, swatch = shown.argb, selected = Backgrounds.indexOf(shown)) { close ->
        Backgrounds.forEachIndexed { i, choice ->
            PickRow(stringResource(choice.name), swatch = choice.argb, divider = i < Backgrounds.lastIndex) { form.background = choice.argb; close() }
        }
    }
}

@Composable
private fun DropdownField(
    text: String,
    enabled: Boolean = true,
    swatch: Int? = null,
    selected: Int = -1,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val scroll = remember(open) { androidx.compose.foundation.ScrollState(with(density) { (selected * 49 - 141).dp.coerceAtLeast(0.dp).roundToPx() }) }
    Box(Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.35f)) {
        Row(
            Modifier.fillMaxSize().clip(FieldShape).background(Am.Card).clickable(enabled = enabled) { open = true }
                .padding(start = 16.dp, end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatch != null) {
                Swatch(swatch)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp, maxLines = 1)
            Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.HomeInk)
        }
        RefMenu(open, { open = false }, ListColor, FieldShape, Modifier.width(180.dp), maxHeight = 330.dp, scroll = scroll) { menu { open = false } }
    }
}

@Composable
private fun PickRow(text: String, swatch: Int? = null, divider: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Swatch(swatch)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Am.OnNavy, fontSize = 16.sp, maxLines = 1)
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(ListLine))
}

@Composable
private fun Swatch(argb: Int) {
    val shape = RoundedCornerShape(5.dp)
    val fill = if (argb ushr 24 == 0) Modifier.checkerboard(3.dp) else Modifier.background(Color(argb))
    Box(Modifier.size(24.dp).clip(shape).then(fill).border(1.dp, Color(0x40808080), shape))
}

@Composable
private fun CustomSize(form: Form) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        NumberField(form.customW, form::editWidth, form::commitSides, Modifier.width(68.dp))
        val tint = if (form.linked) Am.Navy else Am.HomeFaint
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = form::toggleLink), contentAlignment = Alignment.Center) {
            Box(Modifier.size(27.dp).border(1.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(HomeIcons.Link, stringResource(R.string.newproject_link), Modifier.size(20.dp), tint = tint)
            }
            HoldName(stringResource(R.string.newproject_link))
        }
        NumberField(form.customH, form::editHeight, form::commitSides, Modifier.width(68.dp))
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, onLeave: () -> Unit, modifier: Modifier) {
    DoubleTapToType(value, onChange, modifier.fillMaxHeight()) { gate, typed, onTyped ->
        BasicTextField(
            typed, onTyped,
            gate.clip(FieldShape).background(Am.Card).onFocusChanged { if (!it.isFocused) onLeave() },
            textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp, textAlign = TextAlign.Center).withUiFont(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(Am.Navy),
            decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedSizeStrip(form: Form, settings: AppSettings, chips: Boolean, onSaved: () -> Unit) {
    var naming by remember { mutableStateOf<Pair<SavedSize?, String>?>(null) }
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.height(44.dp).clip(FieldShape).border(1.dp, Am.Navy, FieldShape).clickable {
                form.commitSides()
                naming = null to ""
            }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.newproject_save_size_chip), color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        if (chips) settings.savedSizes.forEach { size ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier.height(44.dp).clip(FieldShape).background(Am.Card)
                        .combinedClickable(onLongClick = { menu = true }) { form.pick(size.width, size.height) }.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(size.name, Modifier.widthIn(max = 150.dp), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1)
                    Text(size.label, color = DimText, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                }
                RefMenu(menu, { menu = false }, ListColor, FieldShape, Modifier.width(180.dp)) {
                    PickRow(stringResource(R.string.rename), divider = true) { menu = false; naming = size to size.name }
                    PickRow(stringResource(R.string.delete), divider = false) { menu = false; settings.savedSizes = SavedSizes.without(settings.savedSizes, size) }
                }
            }
        }
    }
    naming?.let { (target, initial) ->
        SizeNameDialog(
            stringResource(if (target == null) R.string.newproject_save_size_title else R.string.rename), initial, dark = false, onDismiss = { naming = null },
            hint = if (target == null) form.size.let { (w, h) -> stringResource(R.string.newproject_save_size_hint, "$w × $h") } else null,
        ) { name ->
            naming = null
            settings.savedSizes = if (target == null) {
                val (w, h) = form.size
                SavedSizes.with(settings.savedSizes, name, w, h).also {
                    form.picked = it.first()
                    onSaved()
                }
            } else SavedSizes.renamed(settings.savedSizes, target, name)
        }
    }
}

@Composable
internal fun SizeNameDialog(title: String, initial: String, dark: Boolean, onDismiss: () -> Unit, hint: String? = null, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ink = if (dark) Am.OnDark else Am.Navy
    val accent = if (dark) Am.Accent else Am.Navy
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title, color = ink) },
        text = {
            BasicTextField(
                text, { text = it.copy(text = it.text.take(SavedSizes.MAX_NAME)) }, Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(6.dp)).background(if (dark) Am.Panel else Am.HomeBg).padding(12.dp),
                textStyle = TextStyle(color = ink, fontSize = 16.sp).withUiFont(), singleLine = true, cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone(text.text) }),
                decorationBox = { inner ->
                    Box {
                        if (hint != null && text.text.isEmpty()) Text(hint, color = ink.copy(alpha = 0.45f), fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        },
        confirmButton = { DialogPill(stringResource(R.string.done)) { onDone(text.text) } },
        dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false, editor = dark, onClick = onDismiss) },
        containerColor = if (dark) Am.Field else Am.Card,
    )
}
