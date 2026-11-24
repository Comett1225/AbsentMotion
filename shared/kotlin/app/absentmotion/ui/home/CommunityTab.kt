package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.formatBytes
import app.absentmotion.library.CommunityClient
import app.absentmotion.library.CommunityDamagedException
import app.absentmotion.library.COMMUNITY_COUNTS_TTL_MS
import app.absentmotion.library.communityDownloadSource
import app.absentmotion.library.CommunityIndex
import app.absentmotion.library.CommunityItem
import app.absentmotion.library.CommunityItemUnavailable
import app.absentmotion.library.CommunityWriteException
import app.absentmotion.platform.MobileDataRefused
import app.absentmotion.library.CommunityKind
import app.absentmotion.ui.editor.effects.AddedEffectDialogs
import app.absentmotion.ui.editor.effects.AddedEffectImport
import app.absentmotion.library.CommunitySort
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.PresetStore
import app.absentmotion.library.addCommunityPreset
import app.absentmotion.library.communityMatches
import app.absentmotion.media.pictureSample
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.Toast
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.SearchField
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.ThemeFade
import java.io.File
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal var sessionIndex: CommunityIndex? = null
internal var sessionAllowed by mutableStateOf(false)

internal class CommunityBrowseState {
    var query by mutableStateOf("")
    var sort by mutableStateOf(CommunitySort.Newest)
    var include by mutableStateOf(emptySet<String>())
    var exclude by mutableStateOf(emptySet<String>())
    fun clear() { query = ""; include = emptySet(); exclude = emptySet() }
    val filtered: Boolean get() = query.isNotBlank() || include.isNotEmpty() || exclude.isNotEmpty()
    fun cycle(tag: String) {
        when (tag) {
            in include -> { include -= tag; exclude += tag }
            in exclude -> exclude -= tag
            else -> include += tag
        }
    }
}

private val sessionBrowse = CommunityBrowseState()

@Composable
internal fun CommunityBrowseControls(state: CommunityBrowseState, items: List<CommunityItem>, modifier: Modifier = Modifier) {
    var sorting by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    val tags = remember(items) { items.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount().toSortedMap(java.text.Collator.getInstance()) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchField(state.query, { state.query = it }, Modifier.weight(1f).heightIn(min = 48.dp),
                hint = stringResource(R.string.community_search), background = Am.Card, content = Am.Navy, faint = Am.HomeDim)
            Box {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Am.Card).clickable { sorting = true }, Alignment.Center) {
                    Icon(HomeIcons.ArrowDown, stringResource(R.string.sounds_sort), Modifier.size(22.dp), tint = Am.Navy)
                }
                DropdownMenu(sorting, { sorting = false }, containerColor = Am.Card) {
                    CommunitySort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (sort == CommunitySort.Newest) R.string.sounds_order_newest else R.string.sounds_order_az), color = Am.Navy) },
                            leadingIcon = { Box(Modifier.size(24.dp)) { if (state.sort == sort) Icon(PanelIcons.Check, null, tint = Am.HomeAccent) } },
                            modifier = Modifier.semantics { selected = state.sort == sort },
                            onClick = { state.sort = sort; sorting = false }
                        )
                    }
                }
            }
            Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(Am.Card).clickable { tagsOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp), Alignment.Center) {
                Text(stringResource(R.string.community_tags), color = Am.Navy, fontSize = 14.sp)
            }
        }
        if (state.include.isNotEmpty() || state.exclude.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (state.include + state.exclude).forEach { tag ->
                    val included = tag in state.include
                    val colour = if (included) Am.HomeAccent else Am.Warning
                    val remove = stringResource(R.string.community_remove_filter, tag)
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Am.Card)
                        .semantics { contentDescription = remove }
                        .clickable { state.include -= tag; state.exclude -= tag }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(if (included) PanelIcons.Check else ChromeIcons.Minus, null, Modifier.size(14.dp), tint = colour)
                        Text(tag, color = colour, fontSize = 12.sp)
                        Icon(HomeIcons.Close, null, Modifier.size(12.dp), tint = colour)
                    }
                }
                Text(stringResource(R.string.community_clear), Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { state.include = emptySet(); state.exclude = emptySet() }.padding(10.dp), color = Am.Navy, fontSize = 13.sp)
            }
        }
    }
    if (tagsOpen) ToolSheet(stringResource(R.string.community_tags), { tagsOpen = false }, closeButton = true) {
        tags.forEach { (tag, count) ->
            val included = tag in state.include
            val excluded = tag in state.exclude
            val colour = if (included) Am.HomeAccent else if (excluded) Am.Warning else Am.Navy
            val status = if (included) stringResource(R.string.community_include_tag, tag) else if (excluded) stringResource(R.string.community_exclude_tag, tag) else tag
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .semantics { stateDescription = status }.clickable { state.cycle(tag) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp), Alignment.Center) {
                    if (included || excluded) Icon(if (included) PanelIcons.Check else ChromeIcons.Minus, null, tint = colour)
                }
                Text(tag, Modifier.weight(1f), color = colour, fontSize = 15.sp)
                Text(count.toString(), color = Am.HomeDim, fontSize = 14.sp)
            }
        }
    }
}

private enum class CommunityCategory(val labelRes: Int, val kind: CommunityKind) {
    Projects(R.string.community_filter_projects, CommunityKind.Bundle),
    Presets(R.string.community_filter_presets, CommunityKind.Preset),
    Effects(R.string.community_filter_effects, CommunityKind.Effect)
}

private class ActiveDownload(
    val http: TrustedHttp,
    val job: Job,
    val cancelled: AtomicBoolean
)

internal const val COMMUNITY_FORM = "https://forms.gle/Xyn4TiKNJ9kUPQ7x8"

@Composable
private fun CommunityShareCard(modifier: Modifier) {
    val links = LocalUriHandler.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getBoolean(SHARE_CARD_HIDDEN, false)) }
    if (hidden) return
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Am.Card).clickable { runCatching { links.openUri(COMMUNITY_FORM) } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.community_share_title), Modifier.weight(1f), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(14.dp)).background(Am.Navy).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.community_share_button), color = Am.OnNavy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.community_share_hint), Modifier.weight(1f), color = Am.HomeDim, fontSize = 12.sp, lineHeight = 16.sp)
            Text(
                stringResource(R.string.community_share_hide),
                Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp)).clickable {
                    hidden = true
                    prefs.edit().putBoolean(SHARE_CARD_HIDDEN, true).apply()
                }.padding(horizontal = 6.dp, vertical = 4.dp),
                color = Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
    }
}

private const val SHARE_CARD_HIDDEN = "communityShareHidden"

@Composable
private fun CommunityFormLine(modifier: Modifier = Modifier) {
    val links = LocalUriHandler.current
    Text(
        stringResource(R.string.community_form_line),
        modifier.clip(RoundedCornerShape(8.dp)).clickable { runCatching { links.openUri(COMMUNITY_FORM) } }.padding(horizontal = 8.dp, vertical = 6.dp),
        color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
    )
}

@Composable
fun CommunityTab(modifier: Modifier, engine: (() -> EngineThread)? = null, onImport: (File) -> Unit) {
    val browse = sessionBrowse
    val context = LocalContext.current
    val isDesktop = PlatformFeatures.desktop
    val scope = rememberCoroutineScope()
    val effectImport = remember(context, engine != null) { engine?.let { AddedEffectImport(context, it) } }
    val pager = rememberPagerState { CommunityCategory.entries.size }
    val client = remember(context) { CommunityClient(context.cacheDir) }
    var index by remember { mutableStateOf(sessionIndex) }
    var loading by remember { mutableStateOf(sessionIndex == null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<CommunityItem?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val allowed = sessionAllowed
    var refused by remember { mutableStateOf(false) }

    LaunchedEffect(attempt, allowed) {
        if (!allowed) return@LaunchedEffect
        val cached = sessionIndex
        if (cached != null) {
            index = cached
            loading = false
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        refused = false
        val fetched = withContext(Dispatchers.IO) {
            try {
                client.index()
            } catch (e: CancellationException) {
                throw e
            } catch (_: app.absentmotion.platform.MobileDataRefused) {
                refused = true
                null
            } catch (_: Exception) {
                null
            }
        }
        if (fetched != null) {
            sessionIndex = fetched
            index = fetched
            failed = false
        } else {
            failed = true
        }
        loading = false
    }

    val current = index
    val pad = if (isDesktop) 24.dp else 16.dp
    val header: @Composable (List<CommunityItem>) -> Unit = { items ->
        Spacer(Modifier.height(if (isDesktop) 16.dp else 8.dp))
        CommunityShareCard(Modifier.padding(horizontal = pad).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CommunityBrowseControls(browse, items, Modifier.padding(horizontal = pad).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CommunityCategoryBar(
            categories = CommunityCategory.entries,
            currentPage = pager.currentPage,
            pageOffset = { pager.currentPage + pager.currentPageOffsetFraction },
            onSelect = { category -> scope.launch { pager.animateScrollToPage(category.ordinal) } },
            modifier = Modifier.padding(horizontal = pad).widthIn(max = 420.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
    }
    var counts by remember(client) { mutableStateOf(emptyMap<String, Long>()) }
    val loadCounts = allowed && current?.tooNew == false && current.items.any { it.asset != null }
    LaunchedEffect(client, loadCounts) {
        if (!loadCounts) return@LaunchedEffect
        while (true) {
            counts = runInterruptible(Dispatchers.IO) { client.downloadCounts() }
            delay(COMMUNITY_COUNTS_TTL_MS)
        }
    }
    when {
        !allowed -> CommunityErrorState(modifier, R.string.community_ask_title, R.string.community_ask_note, R.string.community_ask_button) {
            sessionAllowed = true
        }

        loading -> Column(modifier.fillMaxSize()) {
            header(emptyList())
            CommunitySkeletonGrid(pad)
        }

        failed && refused -> CommunityErrorState(modifier, R.string.community_ask_title, R.string.mobile_data_refused, R.string.community_ask_button) {
            failed = false
            loading = true
            attempt++
        }

        failed || current == null -> CommunityErrorState(modifier) {
            sessionIndex = null
            failed = false
            loading = true
            attempt++
        }

        current.tooNew -> CommunityMessageState(
            modifier = modifier,
            title = stringResource(R.string.community_too_new)
        )

        else -> {
            val itemsByKind = remember(current.items) { current.items.groupBy { it.kind } }
            Column(modifier.fillMaxSize()) {
                header(itemsByKind[CommunityCategory.entries[pager.currentPage].kind].orEmpty())
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondViewportPageCount = 1,
                    verticalAlignment = Alignment.Top
                ) { page ->
                    val category = CommunityCategory.entries[page]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                                alpha = (1f - 0.6f * abs(offset)).coerceIn(0.4f, 1f)
                            }
                    ) {
                        CommunityItemsPage(
                            items = communityMatches(itemsByKind[category.kind].orEmpty(), browse.query, browse.sort, browse.include, browse.exclude),
                            onClear = browse::clear.takeIf { browse.filtered },
                            pad = pad,
                            client = client,
                            engine = engine,
                            thumbs = thumbs,
                            counts = counts,
                            onSelect = { selected = it }
                        )
                    }
                }
            }
        }
    }

    selected?.let { item ->
        CommunityItemSheet(
            item = item,
            thumb = thumbs[item.thumbKey],
            count = item.asset?.let(counts::get),
            onDismiss = { selected = null },
            onImport = onImport,
            onEffect = effectImport?.let { importer ->
                { file -> scope.launch { importer.pickCommunity(file, item) } }
            },
            onPresetBundle = effectImport?.let { importer ->
                { file ->
                    scope.launch {
                        try {
                            app.absentmotion.library.addPresetBundle(context, file, importer::confirmImported)
                            Toast.makeText(context, context.getString(R.string.community_added_preset), Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Toast.makeText(context, context.getString(R.string.community_download_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
        )
    }
    effectImport?.let { AddedEffectDialogs(it) }
}

@Composable
private fun CommunityErrorState(
    modifier: Modifier,
    title: Int = R.string.community_error_title,
    note: Int = R.string.community_error_note,
    button: Int = R.string.community_try_again,
    onRetry: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        Icon(HomeIcons.CommunityTab, null, Modifier.size(40.dp), tint = Am.HomeMid)
        Text(
            text = stringResource(title),
            modifier = Modifier.padding(top = 14.dp),
            color = Am.Navy,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(note),
            modifier = Modifier.padding(top = 8.dp),
            color = Am.HomeMid,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Am.Navy)
                .deskPress(8.dp)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            Alignment.Center
        ) {
            Text(
                text = stringResource(button),
                color = Am.OnNavy,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CommunityMessageState(modifier: Modifier, title: String, note: String? = null) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        Icon(HomeIcons.CommunityTab, null, Modifier.size(40.dp), tint = Am.HomeMid)
        Text(
            text = title,
            modifier = Modifier.padding(top = 14.dp),
            color = Am.Navy,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        if (note != null) {
            Text(
                text = note,
                modifier = Modifier.padding(top = 8.dp),
                color = Am.HomeMid,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CommunityCategoryBar(
    categories: List<CommunityCategory>,
    currentPage: Int,
    pageOffset: () -> Float,
    onSelect: (CommunityCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val barShape = RoundedCornerShape(8.dp)
    val segShape = RoundedCornerShape(6.dp)
    val gap = 3.dp
    val highlightColor = Am.Navy
    val onHighlightColor = Am.OnNavy
    val idleColor = Am.HomeMid
    val count = categories.size
    val pos = pageOffset().coerceIn(0f, (count - 1).toFloat())
    Row(
        modifier
            .background(Am.Card, barShape)
            .border(1.dp, Am.HomeLine, barShape)
            .padding(3.dp)
            .height(34.dp)
            .drawBehind {
                val gapPx = gap.toPx()
                val segWidth = (size.width - gapPx * (count - 1)) / count
                val step = segWidth + gapPx
                val drawPos = pageOffset().coerceIn(0f, (count - 1).toFloat())
                val x = if (layoutDirection == LayoutDirection.Rtl) {
                    size.width - segWidth - drawPos * step
                } else {
                    drawPos * step
                }
                val r = 6.dp.toPx()
                drawRoundRect(
                    color = highlightColor,
                    topLeft = Offset(x, 0f),
                    size = Size(segWidth, size.height),
                    cornerRadius = CornerRadius(r, r)
                )
            },
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        categories.forEachIndexed { index, category ->
            val weight = (1f - abs(pos - index)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(segShape)
                    .deskPress(6.dp)
                    .semantics { selected = currentPage == index }
                    .clickable(role = Role.Tab) { onSelect(category) },
                Alignment.Center
            ) {
                Text(
                    text = stringResource(category.labelRes),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = lerp(idleColor, onHighlightColor, weight),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(9.sp, 13.sp, 0.5.sp)
                )
            }
        }
    }
}

@Composable
private fun CommunityItemsPage(
    items: List<CommunityItem>,
    onClear: (() -> Unit)?,
    pad: Dp,
    client: CommunityClient,
    engine: (() -> EngineThread)?,
    thumbs: MutableMap<String, ImageBitmap?>,
    counts: Map<String, Long>,
    onSelect: (CommunityItem) -> Unit
) {
    val context = LocalContext.current
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(
                text = stringResource(if (onClear == null) R.string.community_empty else R.string.community_no_matches),
                color = Am.HomeMid,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            if (onClear != null) {
                Spacer(Modifier.height(12.dp))
                BigButton(stringResource(R.string.community_clear_search_filters), primary = false, onClick = onClear)
            } else {
                Spacer(Modifier.height(8.dp))
                CommunityFormLine()
            }
        }
    } else {
        LazyVerticalGrid(
            columns = communityCells(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = pad, end = pad, top = 12.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { "${it.kind.id}/${it.name}" }) { item ->
                val key = item.thumbKey
                LaunchedEffect(key) {
                    if (!thumbs.containsKey(key)) {
                        val loaded = withContext(Dispatchers.IO) {
                            try {
                                val bytes = when (item.kind) {
                                    CommunityKind.Bundle -> client.thumbnail(item)
                                    CommunityKind.Preset -> engine?.let { client.presetPicture(item, it()) }
                                    CommunityKind.Effect -> engine?.let { client.effectPicture(item, it(), context) }
                                }
                                Result.success(decodeCommunityThumb(bytes))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                        loaded.onSuccess { thumbs[key] = it }
                    }
                }
                CommunityGridCard(
                    item = item,
                    thumb = thumbs[key],
                    loading = !thumbs.containsKey(key),
                    count = item.asset?.let(counts::get),
                    onClick = { onSelect(item) }
                )
            }
            item(key = "form", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { CommunityFormLine() }
            }
        }
    }
}

@Composable
private fun CommunityGridCard(
    item: CommunityItem,
    thumb: ImageBitmap?,
    loading: Boolean,
    count: Long?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Am.Card)
            .border(1.dp, Am.HomeLine, shape)
            .deskPress(12.dp)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .then(when {
                    thumb != null && item.kind != CommunityKind.Bundle -> Modifier.checkerboard(5.dp)
                    thumb == null && loading -> Modifier.shimmer(RoundedCornerShape(10.dp))
                    else -> Modifier.background(Am.HomePanel)
                }),
            Alignment.Center
        ) {
            androidx.compose.animation.Crossfade(thumb, animationSpec = androidx.compose.animation.core.tween(180), label = "thumb") { shown ->
                if (shown != null) {
                    Image(shown, null, Modifier.fillMaxSize(), contentScale = if (item.kind == CommunityKind.Bundle) ContentScale.Crop else ContentScale.Fit)
                } else if (!loading) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(HomeIcons.CommunityTab, null, Modifier.size(36.dp), tint = Am.HomeMid) }
                }
            }
            count?.let { CommunityDownloadBadge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title.ifEmpty { item.name },
            color = Am.Navy,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (item.creator.isNotEmpty()) {
            Text(
                text = stringResource(R.string.community_by, item.creator),
                modifier = Modifier.padding(top = 2.dp),
                color = Am.HomeMid,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CommunityItemSheet(
    item: CommunityItem,
    thumb: ImageBitmap?,
    count: Long?,
    onDismiss: () -> Unit,
    onImport: (File) -> Unit,
    onEffect: ((File) -> Unit)?,
    onPresetBundle: ((File) -> Unit)?,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var activeDownload by remember(item) { mutableStateOf<ActiveDownload?>(null) }
    val downloading = activeDownload != null
    val addLabel = stringResource(
        if (item.kind == CommunityKind.Preset) R.string.community_add_preset
        else R.string.community_add
    )

    fun stopDownload() {
        val active = activeDownload ?: return
        active.cancelled.set(true)
        active.http.abort()
        active.job.cancel()
        activeDownload = null
    }

    val closeSheet: () -> Unit = {
        stopDownload()
        onDismiss()
    }

    fun startDownload() {
        if (activeDownload != null || (item.kind == CommunityKind.Effect && onEffect == null)) return
        val http = TrustedHttp(communityDownloadSource(item))
        val cancelled = AtomicBoolean(false)
        val job = scope.launch {
            val me = coroutineContext.job
            try {
                val file = withContext(Dispatchers.IO) {
                    val downloaded = runInterruptible { CommunityClient(context.cacheDir) { http }.download(item) }
                    if (item.kind == CommunityKind.Preset) {
                        if (downloaded.extension != "absproj") addCommunityPreset(downloaded, PresetStore(context), MediaAccess.forApp(context))
                        else if (onPresetBundle == null) app.absentmotion.library.addPresetBundle(context, downloaded)
                    }
                    downloaded
                }
                if (cancelled.get()) return@launch
                if (activeDownload?.job === me) {
                    activeDownload = null
                }
                onDismiss()
                when (item.kind) {
                    CommunityKind.Bundle -> onImport(file)
                    CommunityKind.Effect -> onEffect?.invoke(file)
                    CommunityKind.Preset -> {
                        if (file.extension == "absproj" && onPresetBundle != null) onPresetBundle(file)
                        else Toast.makeText(context, context.getString(R.string.community_added_preset), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) {
                if (activeDownload?.job === me) {
                    activeDownload = null
                }
                throw e
            } catch (_: CommunityDamagedException) {
                if (activeDownload?.job === me) {
                    activeDownload = null
                }
                if (!cancelled.get()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.community_download_damaged),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                if (activeDownload?.job === me) {
                    activeDownload = null
                }
                if (!cancelled.get()) {
                    val reason = when {
                        e is MobileDataRefused -> R.string.mobile_data_refused
                        e is CommunityItemUnavailable -> R.string.added_effect_community_unavailable
                        e is CommunityWriteException -> R.string.added_effect_save_failed
                        item.kind == CommunityKind.Effect -> R.string.added_effect_community_unreachable
                        else -> R.string.community_download_failed
                    }
                    Toast.makeText(
                        context,
                        context.getString(reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        if (!job.isCompleted) {
            activeDownload = ActiveDownload(http, job, cancelled)
        }
    }

    ThemeFade.Hold(downloading)
    DisposableEffect(downloading) {
        view.keepScreenOn = downloading
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(item) {
        onDispose { stopDownload() }
    }

    ToolSheet(
        title = item.title.ifEmpty { item.name },
        onBack = closeSheet,
        bottom = {
            Column(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                    if (downloading) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.community_adding),
                                color = Am.Navy,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Am.Accent,
                                trackColor = Am.Navy.copy(alpha = 0.12f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BigButton(
                        label = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        primary = false
                    ) {
                        if (downloading) stopDownload() else closeSheet()
                    }
                    BigButton(
                        label = addLabel,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        icon = HomeIcons.Plus,
                        enabled = !downloading && (item.kind != CommunityKind.Effect || onEffect != null)
                    ) {
                        startDownload()
                    }
                }
            }
        },
        side = { CommunityItemDetails(item, count) }
    ) {
        Spacer(Modifier.height(16.dp))
        val thumbRatio = if (thumb != null && thumb.width > 0 && thumb.height > 0) {
            (thumb.width.toFloat() / thumb.height.toFloat()).coerceIn(0.2f, 5f)
        } else {
            1.5f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(thumbRatio.coerceAtLeast(1f))
                .clip(RoundedCornerShape(12.dp))
                .then(if (thumb != null && item.kind != CommunityKind.Bundle) Modifier.checkerboard(8.dp) else Modifier.background(Am.Scrim)),
            Alignment.Center
        ) {
            if (thumb != null) {
                Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Icon(
                    HomeIcons.CommunityTab,
                    null,
                    Modifier.size(48.dp),
                    tint = Am.OnScrim.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun CommunityItemDetails(item: CommunityItem, count: Long?) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        if (item.creator.isNotEmpty()) {
            Text(
                text = stringResource(R.string.community_by, item.creator),
                color = Am.Navy,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = formatBytes(item.bytes),
            color = Am.HomeDim,
            fontSize = 13.sp
        )
        if (item.description.isNotEmpty()) {
            var expanded by remember(item) { mutableStateOf(false) }
            var overflowed by remember(item) { mutableStateOf(false) }
            Spacer(Modifier.height(14.dp))
            Text(
                text = item.description,
                color = Am.Navy,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                onTextLayout = {
                    if (!expanded) {
                        overflowed = it.hasVisualOverflow
                    }
                }
            )
            if (overflowed || expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (expanded) R.string.community_show_less else R.string.community_read_more
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .deskPress(6.dp)
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    color = Am.HomeAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (item.tags.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = item.tags.joinToString("  \u00B7  "),
                color = Am.HomeDim,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
        if (item.kind == CommunityKind.Effect && item.contents.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.community_effects_included), color = Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            for (effect in item.contents) {
                val label = if (effect.version == 1L) effect.name
                    else stringResource(R.string.community_effect_version, effect.name, effect.version)
                Text(label, Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 13.sp)
            }
        }
        count?.let {
            Text(communityDownloadLabel(it), Modifier.padding(top = 14.dp), color = Am.HomeMid, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun communityDownloadLabel(count: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(count, locale) { NumberFormat.getIntegerInstance(locale).format(count) }
    return pluralStringResource(R.plurals.community_download_count, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), number)
}

@Composable
private fun CommunityDownloadBadge(count: Long, modifier: Modifier) {
    val label = communityDownloadLabel(count)
    val style = TextStyle(fontSize = 10.sp)
    val measure = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = with(LocalDensity.current) { (maxWidth - 12.dp).toPx() }
        if (measure.measure(label, style, maxLines = 1).size.width <= available) {
            Text(
                label, Modifier.background(Am.Card, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Am.HomeMid, style = style, maxLines = 1,
            )
        }
    }
}

private val CommunityItem.thumbKey: String get() = "${kind.id}/$name/$sha256"

internal fun decodeCommunityThumb(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampled = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = pictureSample(maxOf(bounds.outWidth, bounds.outHeight), 512)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun CommunitySkeletonGrid(pad: Dp) {
    LazyVerticalGrid(
        columns = communityCells(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = pad, end = pad, top = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(6) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Am.Card).border(1.dp, Am.HomeLine, shape).padding(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).shimmer(RoundedCornerShape(10.dp)))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(20.dp), Alignment.CenterStart) { Box(Modifier.fillMaxWidth(0.62f).height(12.dp).shimmer(RoundedCornerShape(6.dp))) }
                Box(Modifier.padding(top = 2.dp).height(16.dp), Alignment.CenterStart) { Box(Modifier.fillMaxWidth(0.4f).height(10.dp).shimmer(RoundedCornerShape(5.dp))) }
            }
        }
    }
}

@Composable
private fun communityCells(): GridCells {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return GridCells.Adaptive(if (width >= 900.dp) 220.dp else 160.dp)
}
