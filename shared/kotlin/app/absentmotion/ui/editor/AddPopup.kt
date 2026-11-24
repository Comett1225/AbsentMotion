package app.absentmotion.ui.editor

import app.absentmotion.platform.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.library.SavedTemplate
import app.absentmotion.library.TemplateStore
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.home.rememberFileBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addShape
import app.absentmotion.editor.addText
import app.absentmotion.editor.startFreehand
import app.absentmotion.editor.startVectorDrawing
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale
import app.absentmotion.ui.tutorial.tutorialTarget
import app.absentmotion.ui.tutorial.tutorialGuard
import androidx.compose.foundation.LocalIndication
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.HoldName

internal val AddPopupHeight = 244.dp

private val PopupBar get() = Am.Button
private val TabShadow = Color.Black.copy(alpha = 0.3f)
private val DotIdle get() = Am.Caption
private val Disc get() = Am.Panel

@Composable
fun AddButton(state: EditorState, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    Box(modifier.size(73.dp).tutorialGuard().clickable(interactionSource = press, indication = null) { state.addOpen = true }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(47.dp).pressScale(press).shadow(4.dp, CircleShape).background(Disc, CircleShape).border(1.8.dp, Am.Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ChromeIcons.Plus, stringResource(R.string.add_layer), Modifier.size(24.dp), tint = Am.Accent)
        }
        HoldName(stringResource(R.string.add_layer))
    }
}

private class Tab(@StringRes val label: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab(R.string.add_tab_shape, ChromeIcons.ShapeTab), Tab(R.string.add_tab_media, ChromeIcons.MediaTab), Tab(R.string.add_tab_audio, ChromeIcons.AudioTab),
    Tab(R.string.add_tab_object_asset, ChromeIcons.ObjectTab), Tab(R.string.add_tab_template, ChromeIcons.TemplateTab),
    Tab(R.string.add_tab_tools, app.absentmotion.ui.home.HomeIcons.ToolsTab),
)

@Composable
internal fun AddPopup(state: EditorState, modifier: Modifier = Modifier, library: AddLibrary, side: (@Composable (content: @Composable () -> Unit, rail: @Composable () -> Unit) -> Unit)? = null) {
    var selectedTab by remember { mutableIntStateOf(0) }
    BackHandler(side == null && state.toolOpen == 0) { state.addOpen = false }
    if (side != null) {
        side({
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(42.dp).background(PopupBar), Alignment.Center) {
                    Text(stringResource(Tabs[selectedTab].label), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalSideLibrary provides true) {
                    Swap(selectedTab, Modifier.weight(1f).fillMaxWidth(), rise = 0.dp) { TabPage(selectedTab, state, library) }
                }
            }
        }) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(PopupBar)) {
                Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().heightIn(min = this@BoxWithConstraints.maxHeight), Arrangement.SpaceBetween) {
                        Column(Modifier.padding(top = 4.dp)) {
                            Tabs.forEachIndexed { i, tab -> TabButton(tab, active = i == selectedTab, enabled = true, Modifier.fillMaxWidth().height(58.dp)) { selectedTab = i } }
                        }
                        QuickBar(state, Modifier.fillMaxWidth(), close = false)
                    }
                }
            }
        }
        return
    }
    Row(modifier.clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(PopupBar)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp)) {
                val tabWidth = minOf(64.dp, (maxWidth - 6.dp) / (Tabs.size - 1))
                val scroll = androidx.compose.foundation.rememberScrollState()
                val startFade by animateFloatAsState(if (scroll.canScrollBackward) 1f else 0f, Motion.fast(), label = "tabStart")
                val endFade by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, Motion.fast(), label = "tabEnd")
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Row(Modifier.fillMaxHeight().horizontalScroll(scroll).padding(start = 6.dp)) {
                    Tabs.forEachIndexed { i, tab -> TabButton(tab, active = i == selectedTab, enabled = true, Modifier.width(tabWidth).tutorialTarget(if (i == 1) "add_tab_media" else if (i == 2) "add_tab_audio" else null)) { selectedTab = i } }
                }
                Box(
                    Modifier.align(Alignment.CenterStart).width(24.dp).fillMaxHeight()
                        .graphicsLayer { alpha = startFade }
                        .background(Brush.horizontalGradient(if (rtl) listOf(Color.Transparent, TabShadow) else listOf(TabShadow, Color.Transparent))),
                )
                Box(
                    Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight()
                        .graphicsLayer { alpha = endFade }
                        .background(Brush.horizontalGradient(if (rtl) listOf(TabShadow, Color.Transparent) else listOf(Color.Transparent, TabShadow))),
                )
            }
            val body = Modifier.weight(1f).fillMaxWidth().background(Am.Panel).tutorialTarget(if (selectedTab == 1) "add_media" else if (selectedTab == 2) "add_audio" else null)
            Swap(selectedTab, body, rise = 0.dp) { TabPage(selectedTab, state, library) }
        }
        QuickBar(state, Modifier.width(64.dp).fillMaxHeight())
    }
}

internal val SideRailWidth = 64.dp
internal val LocalSideLibrary = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun TabPage(tab: Int, state: EditorState, library: AddLibrary) {
    when (tab) { 0 -> ShapePages(state, Modifier.fillMaxSize()); 1 -> MediaTab(state, Modifier.fillMaxSize(), library); 3 -> ObjectPage(state, Modifier.fillMaxSize()); 4 -> TemplatePage(state, Modifier.fillMaxSize()); 5 -> ToolsPage(state, Modifier.fillMaxSize()); else -> AudioTab(state, Modifier.fillMaxSize(), library) }
}

@Composable
private fun TabButton(tab: Tab, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (active) Am.Accent else Am.OnDark
    Column(
        modifier.tutorialGuard().fillMaxHeight().alpha(if (enabled) 1f else DimAlpha).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.5.dp))
        Icon(tab.icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(tab.label), Modifier.fillMaxWidth().padding(horizontal = 2.dp), color = tint,
            fontSize = 9.sp, lineHeight = 10.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}

@Composable
private fun ShapePages(state: EditorState, modifier: Modifier) = androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
    val rows = maxOf(3, ((maxHeight - 20.dp) / 61.dp).toInt())
    val columns = maxOf(6, ((maxWidth - 9.6.dp) / 50.84.dp).toInt())
    val cell = minOf(50.84.dp, (maxWidth - 9.6.dp) / columns)
    val pages = ShapeCatalog.all.chunked(rows * columns)
    val pager = rememberPagerState { pages.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, verticalAlignment = Alignment.Top) { page ->
            Column(Modifier.padding(start = 9.6.dp, top = 8.9.dp)) {
                for (row in pages[page].chunked(columns)) {
                    Row {
                        for (def in row) {
                            val press = remember { MutableInteractionSource() }
                            Box(
                                Modifier.size(cell, 61.dp).tutorialGuard().pressScale(press, TilePress).clickable(press, LocalIndication.current) {
                                    state.addShape(def)
                                    state.addOpen = false
                                },
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                ShapeThumbnail(def, state.engine, Modifier.size(40.2.dp))
                                HoldName(stringResource(def.title))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pages.size) { Box(Modifier.size(4.dp).background(if (it == pager.currentPage) Am.OnDark else DotIdle, CircleShape)) }
        }
    }
}

@Composable
private fun ToolsPage(state: EditorState, modifier: Modifier) {
    val importLink = app.absentmotion.ui.home.LocalImportLink.current
    val tools = app.absentmotion.ui.home.projectTools(state.settings, importLink != null)
    LazyHorizontalGrid(AtLeastTwo(96.dp), modifier.clipToBounds()) {
        items(tools, key = { it }) { id ->
            val tool = app.absentmotion.ui.home.toolInfo(id)
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier.size(80.dp, 96.dp).tutorialGuard().pressScale(press, TilePress).clickable(press, LocalIndication.current) { state.openTool(id, importLink) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(tool.icon, null, Modifier.size(28.dp), tint = Am.OnDark)
                Text(stringResource(tool.title), Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp), color = Am.OnDark, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
    }
}

internal fun EditorState.openTool(id: String, importLink: (() -> Unit)?) {
    when (id) {
        "importer" -> importLink?.invoke()
        "send" -> { commitTyping(); endGesture(); toolOpen = 7 }
        else -> toolOpen = app.absentmotion.ui.home.toolInfo(id).open
    }
}

@Composable
private fun TemplatePage(state: EditorState, modifier: Modifier) {
    val context = LocalContext.current
    val saved by produceState(emptyList<SavedTemplate>()) { value = withContext(Dispatchers.IO) { TemplateStore(context).list() } }
    var all by remember { mutableStateOf(false) }
    fun open(t: SavedTemplate) { all = false; state.addOpen = false; EmbedRequest.templateId = t.id }
    LazyHorizontalGrid(AtLeastTwo(96.dp), modifier.clipToBounds()) {
        item(key = "all") {
            val press = remember { MutableInteractionSource() }
            Column(Modifier.size(80.dp, 96.dp).tutorialGuard().pressScale(press, TilePress).clickable(press, LocalIndication.current) { all = true },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MediaGridIcon(Modifier.size(24.dp))
                Text(stringResource(R.string.view_all), color = Am.OnDark, fontSize = 16.sp, maxLines = 1)
            }
        }
        items(saved, key = { it.id }) { t -> TemplateCell(t, Modifier.size(80.dp, 96.dp)) { open(t) } }
    }
    if (all) Dialog({ all = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding().padding(8.dp)) {
            if (saved.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(stringResource(R.string.templates_nothing_here), color = Am.OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(stringResource(R.string.templates_nothing_here_hint), Modifier.padding(top = 8.dp), color = Am.OnDarkDim, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f)) {
                items(saved, key = { it.id }) { t -> TemplateCell(t, Modifier.fillMaxWidth().height(140.dp)) { open(t) } }
            }
            Box(Modifier.fillMaxWidth().height(48.dp).tutorialGuard().clickable { all = false }, Alignment.Center) { Text(stringResource(R.string.action_close), color = Am.Accent, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun TemplateCell(template: SavedTemplate, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(modifier.tutorialGuard().padding(horizontal = 2.5.dp, vertical = 10.dp).pressScale(press, TilePress).background(Color.Black).clickable(press, LocalIndication.current, onClick = onClick)) {
        rememberFileBitmap(template.thumbnail, template.modified)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            template.title, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 3.dp),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickBar(state: EditorState, modifier: Modifier, close: Boolean = true) {
    var captions by remember { mutableStateOf(false) }
    Column(modifier) {
        QuickItem(R.string.add_quick_freehand_brush, ChromeIcons.Freehand, height = 61.dp, iconTop = 7.dp, labelTop = 35.dp, onClick = { state.startFreehand() })
        QuickItem(R.string.add_quick_pen_tool, ChromeIcons.VectorDrawing, height = 61.dp, iconTop = 6.5.dp, labelTop = 35.dp, onClick = { state.startVectorDrawing() })
        QuickItem(R.string.add_quick_text, ChromeIcons.TextTool, height = 48.dp, iconTop = 4.dp, labelTop = 31.dp, target = "add_text", onClick = { state.addText() })
        QuickItem(R.string.add_quick_captions, ChromeIcons.Captions, height = 48.dp, iconTop = 4.dp, labelTop = 31.dp, onClick = { captions = true })
        if (close) BarButton(ChromeIcons.CloseThick, stringResource(R.string.action_close), Modifier.fillMaxWidth().weight(1f)) { state.addOpen = false }
    }
    if (captions) CaptionsSheet(state) { captions = false }
}

@Composable
private fun QuickItem(@StringRes label: Int, icon: ImageVector, height: Dp, iconTop: Dp, labelTop: Dp, target: String? = null, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().height(height).tutorialTarget(target).tutorialGuard().pressScale(press, TilePress).clickable(press, LocalIndication.current, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(iconTop))
        Icon(icon, null, Modifier.size(24.dp), tint = Am.OnDark)
        Spacer(Modifier.height(labelTop - iconTop - 24.dp))
        Text(
            stringResource(label), Modifier.fillMaxWidth().padding(horizontal = 2.dp), color = Am.OnDark,
            fontSize = 9.sp, lineHeight = 10.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
    }
}

internal class AtLeastTwo(private val cell: Dp) : GridCells {
    override fun androidx.compose.ui.unit.Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = maxOf(2, (availableSize + spacing) / (cell.roundToPx() + spacing).coerceAtLeast(1))
        val total = availableSize - spacing * (count - 1)
        return List(count) { total / count + if (it < total % count) 1 else 0 }
    }
}
