package app.absentmotion.ui.editor.effects

import app.absentmotion.editor.addEffectToChosen
import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.text.style.TextOverflow
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.DeskIcon
import app.absentmotion.ui.editor.SearchField
import app.absentmotion.ui.editor.deskScrollbar
import app.absentmotion.ui.editor.deskShortcut
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiTools
import app.absentmotion.ai.QUICK_SHARPEN
import app.absentmotion.audio.AudioFx
import app.absentmotion.audio.FxKind
import app.absentmotion.audio.VoicePreset
import app.absentmotion.audio.VoicePresets
import app.absentmotion.audio.audioEffects
import app.absentmotion.audio.fits
import app.absentmotion.audio.withAudioEffects
import app.absentmotion.audio.withVoicePreset
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectPreset
import app.absentmotion.effects.PresetFavouriteStore
import app.absentmotion.ui.editor.rightClick
import app.absentmotion.model.addEffect
import app.absentmotion.model.effects
import app.absentmotion.ui.editor.CaptionsSheet
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.matchesSearch
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.home.HelpScreen
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.findRootCoordinates
import app.absentmotion.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun EffectBrowser(state: EditorState) {
    val catalog by state.engine.effects.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember(state, context, scope) { AiTools(context.applicationContext, state, scope) }
    val history = remember { context.getSharedPreferences("effects", 0) }
    var recent by remember { mutableStateOf(history.getString("recent", "")!!.split('|').filter { it.isNotEmpty() }) }
    var favourites by remember { mutableStateOf(history.getStringSet("favourites", emptySet())!!.toSet()) }
    val presetFavourites = remember { PresetFavouriteStore.get(context) }
    var favPage by remember { mutableStateOf(false) }
    val layer = state.selected
    val audio = layer?.kind == "audio"
    val hasSound = audio || layer?.mediaFill?.video == true
    var category by remember { mutableStateOf<String?>(state.effectCategory ?: if (audio) "audio" else null) }
    var searching by remember { mutableStateOf(false) }
    var helpPage by remember { mutableStateOf<String?>(null) }
    var captionClip by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf(state.effectDetail?.let(catalog::find)) }
    LaunchedEffect(catalog) { detail = detail?.id?.let(catalog::find) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun hideKeyboard() { focusManager.clearFocus(force = true); keyboard?.hide() }
    fun close() { hideKeyboard(); state.effectDetail = null; state.effectCategory = null; state.effectTargets = null; state.effectBrowserOpen = false }
    var fromAudio by remember { mutableStateOf(false) }
    fun back() {
        when {
            detail != null -> if (state.effectDetail != null) close() else detail = null
            searching -> { hideKeyboard(); searching = false; query = "" }
            fromAudio -> { fromAudio = false; category = "audio" }
            category != null && !audio -> category = null
            else -> close()
        }
    }
    BackHandler(onBack = ::back)
    fun favourite(effect: EffectDefinition) {
        favourites = if (effect.id in favourites) favourites - effect.id else favourites + effect.id
        history.edit().putStringSet("favourites", favourites).apply()
    }
    fun add(effect: EffectDefinition, preset: EffectPreset? = null) {
        if (!effect.available) return
        val targets = state.effectTargets
        if (targets != null) {
            val added = state.addEffectToChosen(targets) { layer: app.absentmotion.model.Layer -> effect.instance(preset, layer.endTime - layer.startTime) }
            if (added == 0) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.effects_paste_no_target), app.absentmotion.platform.Toast.LENGTH_LONG).show()
            recent = (listOf(effect.id) + recent.filterNot { it == effect.id }).take(40)
            history.edit().putString("recent", recent.joinToString("|")).apply()
            close()
            return
        }
        state.editSelected { layer -> layer.addEffect(effect.instance(preset, layer.endTime - layer.startTime)) }
        recent = (listOf(effect.id) + recent.filterNot { it == effect.id }).take(40)
        history.edit().putString("recent", recent.joinToString("|")).apply()
        state.effectIndex = (state.selected?.effects?.size ?: 0) - 1
        close()
    }
    fun pick(effect: EffectDefinition) { if (effect.available) add(effect) else detail = effect }
    fun editAudio(index: Int) {
        state.audioFxFocus = index
        state.panel = EditorPanel.Tools
        state.volumeOpen = true
        close()
    }
    fun addAudio(kind: FxKind) {
        val effects = state.selected?.audioEffects ?: return
        if (!effects.fits(kind)) return
        state.editSelected { it.withAudioEffects(it.audioEffects + AudioFx.of(kind)) }
        editAudio(effects.size)
    }
    fun applyVoice(preset: VoicePreset) {
        state.editSelected { it.withVoicePreset(preset) }
        editAudio(state.selected?.audioEffects?.indexOfFirst { it.kind == FxKind.Voice } ?: -1)
    }
    val audioName = stringResource(R.string.effects_category_audio)
    val voiceName = stringResource(R.string.layer_tool_voice_filters)
    val stepping = (category != null && !audio) || detail != null || searching || fromAudio
    val heading = detail?.name ?: category?.let { key -> when (key) { "audio" -> audioName; "voice" -> voiceName; else -> catalog.categories.firstOrNull { it.first == key }?.second } } ?: stringResource(R.string.effectbrowser_title)
    val listed = catalog.categories.let { all -> if (hasSound) all.filter { it.first != "ai" } + ("audio" to audioName) + all.filter { it.first == "ai" } else all }
    val page = listOf(detail?.id, searching, category)
    fun pageAt() = if (audio || searching || detail != null) -1 else listed.indexOfFirst { it.first == category }
    val at = pageAt()
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val tick = app.absentmotion.ui.editor.rememberHaptic(state)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var shift by remember { mutableFloatStateOf(0f) }
    var slid by remember { mutableStateOf<Any?>(null) }
    val pull = remember { floatArrayOf(0f) }
    val sliding = remember { arrayOfNulls<Job>(1) }
    LaunchedEffect(page) { if (page != slid) slid = null }
    val slide = Modifier.graphicsLayer { translationX = shift }
    fun swipeStart(way: Int): Boolean {
        sliding[0]?.cancel()
        pull[0] = 0f
        return true
    }
    fun swipeDrag(dx: Float, width: Float) {
        pull[0] += dx
        shift = pull[0] * if (EffectSwipe.page(pageAt(), listed.size, pull[0], rtl) != null) 0.25f else 0.08f
    }
    fun swipeEnd(velocity: Float?, width: Float) {
        val to = EffectSwipe.page(pageAt(), listed.size, pull[0], rtl)
        val way = sign(pull[0])
        if (to == null || velocity == null || !EffectSwipe.commits(abs(pull[0]), width, velocity * way, EffectSwipe.PAGE_SHARE, EffectSwipe.FLING_DP * density.density)) {
            sliding[0] = scope.launch { animate(shift, 0f, animationSpec = spring()) { v, _ -> shift = v } }
            return
        }
        val next = listed[to].first
        category = next
        slid = listOf(null, false, next)
        sliding[0] = scope.launch { animate(-way * EffectSwipe.SLIDE_DP * density.density, 0f, animationSpec = Motion.enter()) { v, _ -> shift = v } }
    }
    val rootFocus = remember { FocusRequester() }
    if (PlatformFeatures.desktop) LaunchedEffect(Unit) { rootFocus.requestFocus() }
    fun keyPage(pull: Float, rtl: Boolean): Boolean {
        val to = EffectSwipe.page(pageAt(), listed.size, pull, rtl) ?: return false
        val next = listed[to].first
        category = next
        slid = listOf(null, false, next)
        return true
    }
    fun browserKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || query.isNotEmpty()) return false
        return when {
            event.key == Key.Tab && event.isCtrlPressed -> keyPage(if (event.isShiftPressed) 1f else -1f, false)
            event.key == Key.DirectionRight && !event.isCtrlPressed -> keyPage(-1f, rtl)
            event.key == Key.DirectionLeft && !event.isCtrlPressed -> keyPage(1f, rtl)
            else -> false
        }
    }
    val latestKey by rememberUpdatedState<(androidx.compose.ui.input.key.KeyEvent) -> Boolean> { browserKey(it) }
    if (PlatformFeatures.desktop) DisposableEffect(Unit) {
        val hook: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { latestKey(it) }
        app.absentmotion.ui.editor.DeskDrop.browserKey = hook
        onDispose { if (app.absentmotion.ui.editor.DeskDrop.browserKey === hook) app.absentmotion.ui.editor.DeskDrop.browserKey = null }
    }
    Column(Modifier.fillMaxSize().background(BrowserBg).systemBarsPadding().then(if (PlatformFeatures.desktop) Modifier.focusRequester(rootFocus).onKeyEvent(::browserKey).focusable() else Modifier)) {
        helpPage?.let { page -> HelpScreen(page) { helpPage = null } }
        if (PlatformFeatures.desktop) Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stepping) DeskIcon(ChromeIcons.Back, stringResource(R.string.topbar_back), action = ::back)
            Text(if (searching) stringResource(R.string.effectbrowser_title) else heading, Modifier.weight(1f).padding(start = if (stepping) 4.dp else 12.dp, end = 12.dp), color = Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!audio) SearchField(query, {
                query = it
                if (it.isNotEmpty() && !searching) { state.effectDetail = null; detail = null; searching = true } else if (it.isEmpty() && searching) searching = false
            }, Modifier.width(260.dp).height(36.dp), hint = stringResource(R.string.home_search), background = Am.Panel)
            DeskIcon(ChromeIcons.Close, deskShortcut(R.string.action_close, "Esc")) { close() }
        } else Row(Modifier.fillMaxWidth().height(44.dp).background(BrowserBg), verticalAlignment = Alignment.CenterVertically) {
            BrowserButton(if (stepping) PanelIcons.Back else EffectIcons.Close, stringResource(if (stepping) R.string.topbar_back else R.string.action_close), ::back)
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(query, { query = it }, Modifier.weight(1f).padding(8.dp).focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = Am.OnDark, fontSize = 17.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content), cursorBrush = SolidColor(Am.Accent))
            }
            else Text(heading, Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 1)
            if (audio) Spacer(Modifier.size(44.dp))
            else BrowserButton(EffectIcons.Search, stringResource(R.string.home_search), { state.effectDetail = null; detail = null; searching = true })
        }
        Swap(page, Modifier.fillMaxSize().effectSwipe(at >= 0, ::swipeStart, ::swipeDrag, ::swipeEnd), rise = if (slid == page) 0.dp else 8.dp) {
        if (catalog.message != null && !audio && (app.absentmotion.platform.effectsMessageOnHome() || (category != null && category != "ai" && category != "audio" && category != "voice" && !searching))) {
            Box(Modifier.fillMaxSize().then(slide).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(catalog.message!!, color = Am.OnDark, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else if (detail != null) {
            val effect = detail!!
            Column(Modifier.fillMaxSize().deskVerticalScroll().padding(16.dp)) {
                Thumbnail(catalog, effect.thumbnail, Modifier.align(Alignment.CenterHorizontally).size(160.dp))
                Text(effect.description, Modifier.padding(top = 16.dp, bottom = 4.dp), color = Am.OnDark, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().height(48.dp).clickable { helpPage = "effects/${effect.page}.html" }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ExportIcons.Help, null, Modifier.size(22.dp), tint = Am.OnDark)
                    Text(stringResource(R.string.effect_help), Modifier.padding(start = 12.dp), color = Am.OnDark, fontSize = 15.sp)
                }
                if (state.effectDetail == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.standard_settings), Modifier.weight(1f).height(48.dp).alpha(if (effect.available) 1f else 0.35f)
                        .clickable(enabled = effect.available) { add(effect) }.padding(12.dp), color = Am.OnDark, fontSize = 15.sp)
                    Icon(PanelIcons.Star, stringResource(R.string.effectbrowser_favorite), Modifier.size(48.dp).clickable { favourite(effect) }.padding(12.dp), tint = if (effect.id in favourites) Am.Accent else Am.OnDark)
                }
                if (state.effectDetail == null) effect.presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).fillMaxHeight().alpha(if (effect.available) 1f else 0.35f)
                            .clickable(enabled = effect.available) { add(effect, preset) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumbnail(catalog, effect.thumbnail(preset), Modifier.size(56.dp).clip(RoundedCornerShape(5.dp)))
                            Text(preset.name, Modifier.weight(1f).padding(start = 12.dp), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                        }
                        PresetStar(presetFavourites.contains(effect, preset)) { presetFavourites.toggle(effect, preset) }
                    }
                }
            }
        } else if (!searching && (category == "audio" || category == "voice")) {
            val effects = layer?.audioEffects.orEmpty()
            val audioGrid = rememberLazyGridState()
            LazyVerticalGrid(if (PlatformFeatures.desktop) GridCells.Adaptive(120.dp) else GridCells.Fixed(3), Modifier.fillMaxSize().then(slide).deskScrollbar(audioGrid), audioGrid, contentPadding = PaddingValues(if (PlatformFeatures.desktop) 16.dp else 10.dp)) {
                if (category == "audio") {
                    item(key = "voice-category") { AudioTile(voiceName, 2, true) { fromAudio = true; category = "voice" } }
                    itemsIndexed(AudioOrder) { i, kind ->
                        AudioTile(stringResource(kind.label), i, effects.fits(kind)) { addAudio(kind) }
                    }
                } else {
                    itemsIndexed(VoiceOrder.filterNot { it == FxKind.Voice }) { i, kind -> AudioTile(stringResource(kind.label), i, effects.fits(kind)) { addAudio(kind) } }
                    itemsIndexed(VoicePresets) { i, preset -> AudioTile(stringResource(preset.name), i + 2, effects.any { it.kind == FxKind.Voice } || effects.fits(FxKind.Voice)) { applyVoice(preset) } }
                }
            }
        } else if (searching || category != null) {
            val byName = if (audio || !searching) emptyList() else catalog.search(query)
            val byPreset = if (audio || !searching) emptyList() else catalog.definitions.filter { d -> d !in byName && query.isNotBlank() && d.presets.any { matchesSearch(query, it.name) } }
            val results = if (audio) emptyList() else if (searching) byName + byPreset else catalog.listed(category).sortedBy { it.name.lowercase() }
            val actions = if (app.absentmotion.platform.PlatformFeatures.ai && (searching || category == "ai")) AiAction.entries.filter { it.offered && (!audio || it == AiAction.Noise) && matchesSearch(query, stringResource(it.title)) } else emptyList()
            val resultGrid = key(category) { rememberLazyGridState() }
            LazyVerticalGrid(if (PlatformFeatures.desktop) GridCells.Adaptive(120.dp) else GridCells.Fixed(3), Modifier.fillMaxSize().then(slide).deskScrollbar(resultGrid), resultGrid, contentPadding = PaddingValues(if (PlatformFeatures.desktop) 16.dp else 10.dp)) {
                if (app.absentmotion.platform.PlatformFeatures.ai && hasSound && (category == "ai" || searching && matchesSearch(query, context.getString(R.string.auto_captions)))) {
                    item(key = "ai-captions") { AudioTile(stringResource(R.string.auto_captions), 5, true) { state.playing = false; captionClip = layer.id } }
                }
                items(actions, key = { "ai-${it.name}" }) { action -> AiTile(action, state.selected, ai) }
                items(results, key = { it.id }) { effect -> EffectTile(catalog, effect, 100f, { if (effect in byPreset) detail = effect else pick(effect) }, { detail = effect }, effect.id in favourites) }
            }
        } else if (PlatformFeatures.desktop) {
            DeskEffectsHome(catalog, recent, favourites, presetFavourites, favPage, { favPage = it }, hasSound, audioName, ::pick, { effect, preset -> if (effect.available) add(effect, preset) else detail = effect }, { detail = it }, { category = it })
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val recommended = listOf("absent.chromakey", "absent.blur", "absent.glow").mapNotNull(catalog::find)
                Row(Modifier.fillMaxWidth().height(186.dp).background(RecommendedBg).horizontalScroll(rememberScrollState()).padding(start = 7.5.dp, top = 16.dp), Arrangement.spacedBy(15.dp)) {
                    recommended.forEach { effect -> EffectTile(catalog, effect, 121f, { pick(effect) }, { detail = effect }, effect.id in favourites) }
                }
                Box(Modifier.fillMaxWidth().height(210.dp).pageSwipe(favPage, rtl) { favPage = it }) {
                    Crossfade(favPage, Modifier.matchParentSize(), animationSpec = tween(Motion.FastMs), label = "pages") { fav ->
                    Box(Modifier.fillMaxSize()) {
                    val shown = if (fav) presetFavourites.resolve(catalog.definitions).map { it.first to it.second } +
                        favourites.mapNotNull(catalog::find).map { it to null } else recent.mapNotNull(catalog::find).distinct().take(10).map { it to null }
                    if (fav) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        shown.chunked(2).forEach { column ->
                            Column(Modifier.width(68.dp)) {
                                repeat(2) { row ->
                                    Box(Modifier.height(100.dp)) {
                                        column.getOrNull(row)?.let { (effect, preset) ->
                                            EffectTile(catalog, effect, 62f, { if (preset == null) pick(effect) else if (effect.available) add(effect, preset) else detail = effect },
                                                { detail = effect }, true, preset)
                                        }
                                    }
                                }
                            }
                        }
                    } else Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                        repeat(2) { row ->
                            Row(Modifier.fillMaxWidth().height(100.dp)) {
                                repeat(5) { col ->
                                    Box(Modifier.weight(1f)) {
                                        shown.getOrNull(row * 5 + col)?.first?.let { effect ->
                                            EffectTile(catalog, effect, 62f, { pick(effect) }, { detail = effect }, effect.id in favourites)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) Text(stringResource(if (fav) R.string.effectbrowser_favorite_empty else R.string.effectbrowser_recent_empty),
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    }
                    PageDots(favPage, rtl, tick, Modifier.align(Alignment.BottomCenter)) { favPage = it }
                }
                listed.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(1.5.dp)) {
                        row.forEach { (key, name) ->
                            val press = remember { MutableInteractionSource() }
                            Box(Modifier.weight(1f).height(66.5.dp).padding(bottom = 1.5.dp).pressScale(press, TilePress)
                                .clickable(press, LocalIndication.current) { category = key }, contentAlignment = Alignment.Center) {
                                if (key == "ai") AiBackdrop(Modifier.fillMaxSize())
                                else if (key == "audio") AudioBackdrop(Modifier.fillMaxSize())
                                else Thumbnail(catalog, catalog.listed(key).firstOrNull()?.thumbnail, Modifier.fillMaxSize(), ContentScale.Crop)
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                                Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        }
    }
    AiVideoDialog(ai, state.scene)
    AiNoiseDialog(ai)
    AiCutoutDialog(ai, state.scene)
    AiEnhanceDialog(ai, state.scene) { catalog.find(QUICK_SHARPEN)?.let(::pick) }
    AiProgressDialog(ai)
    captionClip?.let { id -> CaptionsSheet(state, clipId = id) { captionClip = null; close() } }
}

internal val VoiceOrder = listOf(FxKind.HighPitch, FxKind.LowPitch, FxKind.Voice)

internal val AudioOrder = listOf(FxKind.Reverb, FxKind.Echo, FxKind.Eq, FxKind.LowPass, FxKind.HighPass, FxKind.Compressor, FxKind.Gate)

private val BrowserBg get() = Am.Field
private val RecommendedBg get() = Am.PanelInset

@Composable
private fun BrowserButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DeskEffectsHome(
    catalog: EffectCatalog, recent: List<String>, favourites: Set<String>, presetFavourites: app.absentmotion.effects.PresetFavourites, favPage: Boolean, onFavPage: (Boolean) -> Unit,
    hasSound: Boolean, audioName: String, pick: (EffectDefinition) -> Unit, addPreset: (EffectDefinition, EffectPreset) -> Unit, detail: (EffectDefinition) -> Unit, open: (String) -> Unit,
) {
    val recommended = remember(catalog) { listOf("absent.chromakey", "absent.blur", "absent.glow").mapNotNull(catalog::find) }
    val shown: List<Pair<EffectDefinition, EffectPreset?>> = if (favPage) presetFavourites.resolve(catalog.definitions).map { it.first to it.second } + favourites.mapNotNull(catalog::find).map { it to null }
        else recent.mapNotNull(catalog::find).distinct().take(12).map { it to null }
    val listed = catalog.categories.let { all -> if (hasSound) all.filter { it.first != "ai" } + ("audio" to audioName) + all.filter { it.first == "ai" } else all }
    Column(Modifier.fillMaxSize().deskVerticalScroll().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.desk_recommended), color = Am.OnDarkDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(RecommendedBg).padding(8.dp), Arrangement.spacedBy(8.dp)) {
                recommended.forEach { effect -> EffectTile(catalog, effect, 112f, { pick(effect) }, { detail(effect) }, effect.id in favourites) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DeskTab(stringResource(R.string.effectbrowser_recent), !favPage) { onFavPage(false) }
                DeskTab(stringResource(R.string.effectbrowser_favorite), favPage) { onFavPage(true) }
            }
            if (shown.isEmpty()) Text(stringResource(if (favPage) R.string.effectbrowser_favorite_empty else R.string.effectbrowser_recent_empty), Modifier.padding(vertical = 12.dp), color = Am.OnDarkDim, fontSize = 13.sp)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { (effect, preset) ->
                    EffectTile(catalog, effect, 96f, { if (preset == null) pick(effect) else addPreset(effect, preset) }, { detail(effect) }, preset != null || effect.id in favourites, preset)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 220.dp).toInt().coerceIn(2, 6)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listed.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        row.forEach { (key, name) ->
                            Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(8.dp)).hoverFill(8.dp, Color.White.copy(alpha = .12f)).handCursor().clickable { open(key) }, Alignment.Center) {
                                if (key == "ai") AiBackdrop(Modifier.fillMaxSize())
                                else if (key == "audio") AudioBackdrop(Modifier.fillMaxSize())
                                else Thumbnail(catalog, catalog.listed(key).firstOrNull()?.thumbnail, Modifier.fillMaxSize(), ContentScale.Crop)
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                                Text(name, Modifier.padding(horizontal = 8.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeskTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (selected) Am.Accent.copy(alpha = .2f) else Color.Transparent).hoverFill(15.dp, Am.Field).handCursor().clickable(onClick = onClick).padding(horizontal = 14.dp), Alignment.Center) {
        Text(label, color = if (selected) Am.Accent else Am.OnDarkDim, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun EffectTile(catalog: EffectCatalog, effect: EffectDefinition, width: Float, onClick: () -> Unit, onLongClick: () -> Unit, favourite: Boolean, preset: EffectPreset? = null) {
    val press = remember { MutableInteractionSource() }
    Column(Modifier.width(width.dp).pressScale(press, TilePress).alpha(if (effect.available) 1f else 0.35f).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, Am.Field).handCursor()
        .rightClick(onLongClick).combinedClickable(press, LocalIndication.current, onClick = onClick, onLongClick = onLongClick).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Thumbnail(catalog, preset?.let(effect::thumbnail) ?: effect.thumbnail, Modifier.size((width - 7).dp).clip(RoundedCornerShape(5.dp)))
            if (favourite) Icon(PanelIcons.Star, null, Modifier.align(Alignment.TopEnd).size(14.dp), tint = Am.Accent)
            if (preset == null && catalog.isNew(effect)) Text(stringResource(R.string.t_new), Modifier.align(Alignment.TopStart).padding(3.dp).clip(RoundedCornerShape(3.dp)).background(Am.Accent)
                .padding(horizontal = 3.dp), color = Color.White, fontSize = if (PlatformFeatures.desktop) 12.sp else 7.sp, lineHeight = if (PlatformFeatures.desktop) 14.sp else 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(preset?.name ?: effect.name, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDark, fontSize = if (PlatformFeatures.desktop) 12.sp else if (width > 100) 10.sp else 8.sp,
            lineHeight = if (PlatformFeatures.desktop) 14.sp else 9.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PresetStar(favourite: Boolean, onToggle: () -> Unit) {
    Box(Modifier.size(48.dp).toggleable(favourite, role = Role.Checkbox) { onToggle() }, contentAlignment = Alignment.Center) {
        app.absentmotion.ui.editor.DeskTip(stringResource(R.string.effectbrowser_favorite_preset)) { Icon(PanelIcons.Star, stringResource(R.string.effectbrowser_favorite_preset), Modifier.size(24.dp), tint = if (favourite) Am.Accent else Am.OnDark) }
    }
}

@Composable
internal fun Thumbnail(catalog: EffectCatalog, path: String?, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    Box(modifier.background(RecommendedBg)) { app.absentmotion.platform.EffectPicture(catalog, path, scale) }
}

internal object EffectSwipe {
    const val EDGE_DP = 24f
    const val FLING_DP = 800f
    const val SLIDE_DP = 40f
    const val DELETE_SHARE = 0.4f
    const val PAGE_SHARE = 0.25f

    fun direction(dx: Float, dy: Float, slop: Float): Int? = when {
        dx * dx + dy * dy <= slop * slop -> null
        abs(dx) > abs(dy) -> if (dx < 0f) -1 else 1
        else -> 0
    }

    fun fromEdge(x: Float, width: Float, edge: Float): Boolean = x < edge || x > width - edge

    fun commits(travel: Float, width: Float, velocity: Float, share: Float, fling: Float): Boolean =
        velocity >= fling || (travel >= width * share && velocity > -fling)

    fun page(at: Int, count: Int, pull: Float, rtl: Boolean): Int? {
        if (at < 0 || pull == 0f) return null
        return (at + if ((pull < 0f) != rtl) 1 else -1).takeIf { it in 0 until count }
    }
}

@Composable
internal fun Modifier.effectSwipe(enabled: Boolean, accept: (Int) -> Boolean, drag: (Float, Float) -> Unit, end: (Float?, Float) -> Unit): Modifier {
    if (PlatformFeatures.desktop) return this
    val on by rememberUpdatedState(enabled)
    val begin by rememberUpdatedState(accept)
    val move by rememberUpdatedState(drag)
    val finish by rememberUpdatedState(end)
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onPlaced { place[0] = it }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val at = place[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
            if (!on || EffectSwipe.fromEdge(at.localToRoot(down.position).x, at.findRootCoordinates().size.width.toFloat(), EffectSwipe.EDGE_DP.dp.toPx())) return@awaitEachGesture
            var change = down
            var way: Int? = null
            while (way == null) {
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                way = EffectSwipe.direction(change.position.x - down.position.x, change.position.y - down.position.y, viewConfiguration.touchSlop)
            }
            if (way == 0 || !begin(way)) return@awaitEachGesture
            change.consume()
            val width = size.width.toFloat()
            val tracker = VelocityTracker().apply { addPointerInputChange(change) }
            while (true) {
                val next = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (next == null || (!next.pressed && next.isConsumed)) { finish(null, width); return@awaitEachGesture }
                tracker.addPointerInputChange(next)
                val dx = next.position.x - next.previousPosition.x
                next.consume()
                if (!next.pressed) break
                move(dx, width)
            }
            finish(tracker.calculateVelocity().x, width)
        }
    }
}
