package app.absentmotion.ui.editor.effects

import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.adaptive.deskLayout
import app.absentmotion.editor.addEffectToChosen
import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.LocalContext
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
import app.absentmotion.effects.AddedEffectCategory
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectPreset
import app.absentmotion.effects.EffectRecommendationTarget
import app.absentmotion.effects.EffectTargetKind
import app.absentmotion.effects.PresetFavouriteStore
import app.absentmotion.effects.recommendedEffects
import app.absentmotion.ui.editor.rightClick
import app.absentmotion.model.Layer
import app.absentmotion.model.addEffect
import app.absentmotion.model.canAnimate
import app.absentmotion.model.effectTargets
import app.absentmotion.model.effects
import app.absentmotion.model.isKeyframed
import app.absentmotion.ui.editor.CaptionsSheet
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.FavouriteStar
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.matchesSearch
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.home.HelpScreen
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.withUiFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import app.absentmotion.ui.HoldName

internal val LocalBlockedCategory = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

internal fun EffectDefinition.usable(blocked: String?): Boolean = available && (blocked == null || category != blocked)

internal fun blockedCategory(layer: app.absentmotion.model.Layer?): String? = if (layer?.kind == "model") "3d" else null

private val LocalAddedEffectImport = staticCompositionLocalOf<AddedEffectImport?> { null }
private const val CreatorCategoryPrefix = "creator:"

private val AnimatedTransforms = setOf("location", "rotation", "scale", "tilt", "skew")

private fun Layer.effectTargetKind(): EffectTargetKind {
    val media = mediaFill
    return when {
        kind == "text" -> EffectTargetKind.Text
        kind == "embedScene" -> EffectTargetKind.Group
        kind == "model" -> EffectTargetKind.Model
        media?.video == true -> EffectTargetKind.Video
        media != null -> EffectTargetKind.Photo
        else -> EffectTargetKind.Shape
    }
}

private fun Layer.recommendationTarget(look: app.absentmotion.effects.MediaLook, beats: Boolean, totalTime: Int): EffectRecommendationTarget {
    val target = effectTargetKind()
    val animated = node.child("transform")?.children.orEmpty().any {
        it.name in AnimatedTransforms && it.isKeyframed
    }
    val (hidden, active) = effects.partition { it["hidden"] == "true" }
    return EffectRecommendationTarget(target, animated, active.mapNotNull { it["id"] }.toSet(), hidden.mapNotNull { it["id"] }.toSet(), look, (endTime - startTime).let { it in 1..1500 && it * 3 <= totalTime }, beats)
}

@Composable
fun EffectBrowser(state: EditorState) {
    val blocked = if (state.effectTargets == null) blockedCategory(state.selected) else null
    val context = LocalContext.current
    val importer = remember(state.engine, context) { AddedEffectImport(context) { state.engine } }
    CompositionLocalProvider(LocalBlockedCategory provides blocked, LocalAddedEffectImport provides importer) {
        val pickFile = rememberAddedEffectPicker(importer)
        BrowserBody(state, pickFile)
        AddedEffectDialogs(importer, pickFile = pickFile)
    }
}

@Composable
private fun BrowserBody(state: EditorState, pickFile: () -> Unit) {
    val blocked = LocalBlockedCategory.current
    val catalog by state.engine.effects.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember(state, context, scope) { AiTools(context.applicationContext, state, scope) }
    val history = remember { context.getSharedPreferences("effects", 0) }
    var recent by remember { mutableStateOf(history.getString("recent", "")!!.split('|').filter { it.isNotEmpty() }) }
    var favourites by remember { mutableStateOf(history.getStringSet("favourites", emptySet())!!.toSet()) }
    var habits by remember { mutableStateOf(app.absentmotion.effects.EffectHabits.decode(history.getString("habits", null))) }
    val shownRecommended = remember { arrayOf(emptyList<String>()) }
    val presetFavourites = remember { PresetFavouriteStore.get(context) }
    var favPage by remember { mutableStateOf(false) }
    val layer = state.selected
    val audio = layer?.kind == "audio"
    val hasSound = audio || layer?.mediaFill?.video == true
    var category by remember { mutableStateOf<String?>(state.effectCategory ?: if (audio) "audio" else null) }
    var creator by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var helpPage by remember { mutableStateOf<String?>(null) }
    var captionClip by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf(state.effectDetail?.let(catalog::find)) }
    LaunchedEffect(catalog) { detail = detail?.id?.let(catalog::find) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun hideKeyboard() { focusManager.clearFocus(force = true); keyboard?.hide() }
    fun close() { hideKeyboard(); state.effectBrowserOpen = false }
    var fromAudio by remember { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val definitions = catalog.definitions
    val creators = remember(definitions) {
        definitions.filter { it.isAdded }.groupBy { it.addedCreator }.mapValues { (_, effects) ->
            effects.sortedBy { it.name.lowercase() }
        }
    }
    val otherCreators = stringResource(R.string.added_effect_other_creators)
    fun creatorName(effects: List<EffectDefinition>) =
        effects.maxByOrNull { it.addedModified }?.addedAuthor?.ifBlank { otherCreators } ?: otherCreators
    val categoryCreator = category?.takeIf { it.startsWith(CreatorCategoryPrefix) }?.removePrefix(CreatorCategoryPrefix)
    val creatorPage = categoryCreator ?: creator
    LaunchedEffect(creators, creatorPage) {
        if (creatorPage != null && creatorPage !in creators) {
            if (categoryCreator != null) category = null else creator = null
        }
    }
    val grids = remember { HashMap<String, androidx.compose.foundation.lazy.grid.LazyGridState>() }
    fun back() {
        when {
            detail != null -> if (state.effectDetail != null) close() else detail = null
            searching -> { hideKeyboard(); searching = false; query = ""; grids.keys.removeAll { it.startsWith("search:") } }
            creator != null -> creator = null
            fromAudio -> { fromAudio = false; category = "audio" }
            category != null && !audio -> { category = null; grids.clear() }
            else -> close()
        }
    }
    BackHandler(onBack = ::back)
    fun favourite(effect: EffectDefinition) {
        favourites = if (effect.id in favourites) favourites - effect.id else favourites + effect.id
        history.edit().putStringSet("favourites", favourites).apply()
    }
    fun learn(effect: EffectDefinition) {
        val kinds = (state.effectTargets?.let { state.scene.effectTargets(it) } ?: listOfNotNull(state.selected)).map { it.effectTargetKind() }
        if (kinds.isEmpty()) return
        habits = habits.used(kinds, effect.id, System.currentTimeMillis()).ignored(kinds, shownRecommended[0] - effect.id)
        history.edit().putString("habits", habits.encode()).apply()
    }
    fun add(effect: EffectDefinition, preset: EffectPreset? = null) {
        if (!effect.usable(blocked)) return
        val targets = state.effectTargets
        if (targets != null) {
            val added = state.addEffectToChosen(targets, skip = { !effect.usable(blockedCategory(it)) }) { layer: app.absentmotion.model.Layer -> effect.instance(preset, layer.endTime - layer.startTime) }
            if (added == 0) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.effects_paste_no_target), app.absentmotion.platform.Toast.LENGTH_LONG).show() else learn(effect)
            recent = (listOf(effect.id) + recent.filterNot { it == effect.id }).take(40)
            history.edit().putString("recent", recent.joinToString("|")).apply()
            close()
            return
        }
        learn(effect)
        state.editSelected { layer -> layer.addEffect(effect.instance(preset, layer.endTime - layer.startTime)) }
        recent = (listOf(effect.id) + recent.filterNot { it == effect.id }).take(40)
        history.edit().putString("recent", recent.joinToString("|")).apply()
        state.effectIndex = (state.selected?.effects?.size ?: 0) - 1
        close()
    }
    fun pick(effect: EffectDefinition) { if (!effect.isAdded && effect.usable(blocked)) add(effect) else detail = effect }
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
    val addedName = stringResource(R.string.added_effect_category)
    val stepping = (category != null && !audio) || detail != null || searching || fromAudio
    val categoryNames = catalog.categories.toMap()
    val heading = detail?.let { categoryNames[it.category] ?: it.name } ?: when {
        creatorPage != null -> creatorName(creators[creatorPage].orEmpty())
        category == "audio" -> audioName
        category == "voice" -> voiceName
        category == AddedEffectCategory -> addedName
        else -> categoryNames[category] ?: stringResource(R.string.effectbrowser_title)
    }
    val creatorTiles = creators.entries.sortedBy { creatorName(it.value).lowercase() }
    val creatorCategories = creatorTiles.filter { it.key in catalog.shownAddedCreators }
        .map { (slug, effects) -> (CreatorCategoryPrefix + slug) to creatorName(effects) }
    val listed = catalog.categories.let { all -> if (hasSound) all.filter { it.first != "ai" } + ("audio" to audioName) + all.filter { it.first == "ai" } else all } +
        (AddedEffectCategory to addedName) + creatorCategories
    val page = listOf(searching, category, creator)
    var shownDetail by remember { mutableStateOf<EffectDefinition?>(null) }
    if (detail != null) shownDetail = detail
    fun pageAt() = if (audio || searching || detail != null || creator != null) -1 else listed.indexOfFirst { it.first == category }
    val at = pageAt()
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val tick = app.absentmotion.ui.editor.rememberHaptic(state)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var shift by remember { mutableFloatStateOf(0f) }
    var slid by remember { mutableStateOf<Any?>(null) }
    val pull = remember { floatArrayOf(0f) }
    val sliding = remember { arrayOfNulls<Job>(1) }
    LaunchedEffect(page) { if (page != slid) slid = null }
    val depth = when { creator != null && !searching -> 2; searching || category != null -> 1; else -> 0 }
    val lastDepth = remember { intArrayOf(depth) }
    val way = remember(page) { (depth - lastDepth[0]).sign.also { lastDepth[0] = depth } }
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
        slid = listOf(false, next, null)
        sliding[0] = scope.launch { animate(-way * EffectSwipe.SLIDE_DP * density.density, 0f, animationSpec = Motion.enter()) { v, _ -> shift = v } }
    }
    val rootFocus = remember { FocusRequester() }
    if (deskLayout) LaunchedEffect(Unit) { rootFocus.requestFocus() }
    fun keyPage(pull: Float, rtl: Boolean): Boolean {
        val to = EffectSwipe.page(pageAt(), listed.size, pull, rtl) ?: return false
        val next = listed[to].first
        category = next
        slid = listOf(false, next, null)
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
    if (deskLayout) DisposableEffect(Unit) {
        val hook: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { latestKey(it) }
        app.absentmotion.ui.editor.DeskDrop.browserKey = hook
        onDispose { if (app.absentmotion.ui.editor.DeskDrop.browserKey === hook) app.absentmotion.ui.editor.DeskDrop.browserKey = null }
    }
    val twoPane = app.absentmotion.ui.adaptive.LocalAdaptiveSpace.current?.wide == true
    val recommended = if (!audio && !searching && category == null && detail == null) {
        val selected = state.effectTargets?.let { state.scene.effectTargets(it) } ?: listOfNotNull(layer?.takeIf { it.canAnimate })
        val medias = selected.mapNotNull { it.mediaFill }.distinct()
        val looks by androidx.compose.runtime.produceState(medias.associateWith { knownLook(it) }, medias) {
            value = medias.associateWith { mediaLookOf(context, it) }
        }
        val beats = state.scene.bookmarks.size >= 4
        val targets = selected.map { it.recommendationTarget(it.mediaFill?.let(looks::get) ?: app.absentmotion.effects.MediaLook(), beats, state.scene.totalTime) }
        val blockedCategories = selected.mapNotNull(::blockedCategory).toSet()
        remember(catalog, targets, blockedCategories, recent, favourites, habits) {
            recommendedEffects(catalog.definitions.filter { !it.isAdded && it.category !in blockedCategories }, targets, recent, favourites, habits, System.currentTimeMillis())
        }
    } else emptyList()
    if (recommended.isNotEmpty()) shownRecommended[0] = recommended.map { it.id }
    Column(Modifier.fillMaxSize().background(BrowserBg).systemBarsPadding().then(if (deskLayout) Modifier.focusRequester(rootFocus).onKeyEvent(::browserKey).focusable() else Modifier)) {
        helpPage?.let { page -> HelpScreen(page) { helpPage = null } }
        if (deskLayout) Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    textStyle = TextStyle(color = Am.OnDark, fontSize = 17.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content).withUiFont(), cursorBrush = SolidColor(Am.Accent))
            }
            else Text(heading, Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 1)
            if (audio) Spacer(Modifier.size(44.dp))
            else BrowserButton(EffectIcons.Search, stringResource(R.string.home_search), { state.effectDetail = null; detail = null; searching = true })
        }
        val detailPane: @Composable () -> Unit = {
        androidx.compose.animation.AnimatedVisibility(
            detail != null,
            enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(300, easing = Motion.EaseOut)) { if (rtl) -it else it },
            exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240, easing = Motion.EaseIn)) { if (rtl) -it else it },
        ) {
            shownDetail?.let { effect ->
                Column(Modifier.fillMaxSize().background(BrowserBg).pointerInput(Unit) {}.deskVerticalScroll()) {
                    EffectHero(catalog, effect)
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (!effect.isAdded) Row(Modifier.fillMaxWidth().height(48.dp).clickable { helpPage = "effects/${effect.page}.html" }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ExportIcons.Help, null, Modifier.size(22.dp), tint = Am.OnDark)
                        Text(stringResource(R.string.effect_help), Modifier.padding(start = 12.dp), color = Am.OnDark, fontSize = 15.sp)
                    }
                    if (state.effectDetail == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.standard_settings), Modifier.weight(1f).height(48.dp).alpha(if (effect.usable(blocked)) 1f else 0.35f)
                            .clickable(enabled = effect.usable(blocked)) { add(effect) }.padding(12.dp), color = Am.OnDark, fontSize = 15.sp)
                        FavouriteStar(effect.id in favourites, { favourite(effect) }, stringResource(R.string.effectbrowser_favorite), Modifier.size(48.dp))
                    }
                    if (state.effectDetail == null) effect.presets.forEach { preset ->
                        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).fillMaxHeight().alpha(if (effect.usable(blocked)) 1f else 0.35f)
                                .clickable(enabled = effect.usable(blocked)) { add(effect, preset) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Thumbnail(catalog, effect.thumbnail(preset), Modifier.size(56.dp).clip(RoundedCornerShape(5.dp)))
                                Text(preset.name, Modifier.weight(1f).padding(start = 12.dp), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                            }
                            FavouriteStar(presetFavourites.contains(effect, preset), { presetFavourites.toggle(effect, preset) }, stringResource(R.string.effectbrowser_favorite_preset), Modifier.size(48.dp))
                        }
                    }
                    }
                }
            }
        }
        }
        Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(2f).fillMaxHeight()) {
        Swap(page, Modifier.fillMaxSize().effectSwipe(at >= 0, ::swipeStart, ::swipeDrag, ::swipeEnd), rise = if (slid == page || way != 0) 0.dp else 8.dp, shift = if (slid == page) 0.dp else 48.dp * way * (if (rtl) -1 else 1)) {
        if (catalog.message != null && !audio && creatorPage == null && category != AddedEffectCategory &&
            (app.absentmotion.platform.effectsMessageOnHome() ||
                (category != null && category != "ai" && category != "audio" && category != "voice" && !searching && catalog.listed(category).isEmpty()))
        ) {
            Box(Modifier.fillMaxSize().then(slide).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(catalog.message!!, color = Am.OnDark, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else if (!searching && (category == "audio" || category == "voice")) {
            val effects = layer?.audioEffects.orEmpty()
            val audioGrid = grids.getOrPut(category.orEmpty()) { androidx.compose.foundation.lazy.grid.LazyGridState() }
            LazyVerticalGrid(if (deskLayout || twoPane) GridCells.Adaptive(120.dp) else GridCells.Fixed(3), Modifier.fillMaxSize().then(slide).deskScrollbar(audioGrid), audioGrid, contentPadding = PaddingValues(if (deskLayout) 16.dp else 10.dp)) {
                if (category == "audio") {
                    item(key = "voice-category", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { AudioFolderTile(voiceName, VoiceOrder.count { it != FxKind.Voice } + VoicePresets.size) { fromAudio = true; category = "voice" } }
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
            val addedPage = !searching && category == AddedEffectCategory
            val results = when {
                !searching && creatorPage != null -> creators[creatorPage].orEmpty()
                addedPage || audio -> emptyList()
                searching -> byName + byPreset
                else -> catalog.listed(category).sortedWith(compareBy({ it.isAdded }, { it.name.lowercase() }))
            }
            val actions = if (app.absentmotion.platform.PlatformFeatures.ai && (searching || category == "ai")) AiAction.entries.filter { it.offered && (!audio || it == AiAction.Noise) && matchesSearch(query, stringResource(it.title)) } else emptyList()
            val resultGrid = grids.getOrPut(if (searching) "search:$query" else creatorPage?.let { CreatorCategoryPrefix + it } ?: category.orEmpty()) { androidx.compose.foundation.lazy.grid.LazyGridState() }
            LazyVerticalGrid(if (deskLayout || twoPane) GridCells.Adaptive(120.dp) else GridCells.Fixed(3), Modifier.fillMaxSize().then(slide).deskScrollbar(resultGrid), resultGrid, contentPadding = PaddingValues(if (deskLayout) 16.dp else 10.dp)) {
                if (app.absentmotion.platform.PlatformFeatures.ai && hasSound && (category == "ai" || searching && matchesSearch(query, context.getString(R.string.auto_captions)))) {
                    item(key = "ai-captions") { AudioTile(stringResource(R.string.auto_captions), 5, true) { state.playing = false; captionClip = layer.id } }
                }
                if (addedPage && creator == null) {
                    item(key = "add-effect") { AddEffectTile(100f, pickFile) }
                    items(creatorTiles, key = { "creator-${it.key}" }) { (slug, effects) ->
                        AddedCreatorTile(catalog, slug, creatorName(effects), effects.firstNotNullOfOrNull { it.addedPicture }, 100f) { creator = slug }
                    }
                    if (creators.isEmpty()) item(key = "added-empty", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { AddedEffectsEmpty() }
                }
                items(actions, key = { "ai-${it.name}" }) { action -> AiTile(action, state.selected, ai) }
                items(results, key = { it.shortcut ?: it.id }) { effect -> EffectTile(catalog, effect, 100f, { if (effect in byPreset) detail = effect else pick(effect) }, { detail = effect }, effect.id in favourites) }
            }
        } else if (deskLayout) {
            DeskEffectsHome(homeScroll, catalog, recommended, recent, favourites, presetFavourites, favPage, { favPage = it }, listed, creators, ::pick, { effect, preset -> if (effect.usable(blocked)) add(effect, preset) else detail = effect }, { detail = it }, { category = it })
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(homeScroll)) {
                RecommendedEffects(catalog, recommended, favourites, ::pick) { detail = it }
                Box(Modifier.fillMaxWidth().height(210.dp).then(if (twoPane) Modifier else Modifier.pageSwipe(favPage, rtl) { favPage = it })) {
                    Crossfade(favPage && !twoPane, Modifier.matchParentSize(), animationSpec = tween(Motion.FastMs), label = "pages") { fav ->
                    Box(Modifier.fillMaxSize()) {
                    val shown = if (fav) presetFavourites.resolve(catalog.definitions).map { it.first to it.second } +
                        favourites.mapNotNull(catalog::find).map { it to null } else recent.mapNotNull(catalog::find).distinct().take(10).map { it to null }
                    if (fav) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        shown.chunked(2).forEach { column ->
                            Column(Modifier.width(68.dp)) {
                                repeat(2) { row ->
                                    Box(Modifier.height(100.dp)) {
                                        column.getOrNull(row)?.let { (effect, preset) ->
                                            EffectTile(catalog, effect, 62f, { if (preset == null) pick(effect) else if (effect.usable(blocked)) add(effect, preset) else detail = effect },
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
                    if (!twoPane) PageDots(favPage, rtl, tick, Modifier.align(Alignment.BottomCenter)) { favPage = it }
                }
                listed.chunked(if (twoPane) 4 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(1.5.dp)) {
                        row.forEach { (key, name) ->
                            EffectsCategoryTile(catalog, key, name, creators, Modifier.weight(1f).height(66.5.dp).padding(bottom = 1.5.dp)) { category = key }
                        }
                        repeat((if (twoPane) 4 else 2) - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        }
        if (!twoPane) detailPane()
        }
        if (twoPane) Box(Modifier.weight(1f).fillMaxHeight().background(RecommendedBg)) {
            val starred = presetFavourites.resolve(catalog.definitions).map { it.first to it.second } + favourites.mapNotNull(catalog::find).map { it to null }
            if (starred.isEmpty()) Text(stringResource(R.string.effectbrowser_favorite_empty), Modifier.align(Alignment.Center).padding(horizontal = 24.dp), color = Am.OnDarkDim, fontSize = 15.sp, textAlign = TextAlign.Center)
            else LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.effectbrowser_favorite), Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp), color = Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                items(starred) { (effect, preset) -> EffectTile(catalog, effect, 100f, { if (preset == null) pick(effect) else if (effect.usable(blocked)) add(effect, preset) else detail = effect }, { detail = effect }, true, preset) }
            }
            detailPane()
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
        HoldName(description)
    }
}

@Composable
private fun RecommendedEffects(
    catalog: EffectCatalog, effects: List<EffectDefinition>, favourites: Set<String>,
    pick: (EffectDefinition) -> Unit, detail: (EffectDefinition) -> Unit,
) {
    if (effects.isEmpty()) return
    val padding = if (deskLayout) 8.dp else 10.dp
    val gap = 8.dp
    Column(
        Modifier.fillMaxWidth().then(if (deskLayout) Modifier else Modifier.background(RecommendedBg).padding(top = 8.dp)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.desk_recommended), Modifier.semantics { heading() }.padding(start = if (deskLayout) 0.dp else padding),
            color = Am.OnDarkDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maximum = if (deskLayout) 112.dp else 121.dp
            val width = ((maxWidth - padding * 2 - gap * 2) / 3).coerceIn(24.dp, maximum)
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(RecommendedBg).padding(horizontal = padding, vertical = 4.dp), Arrangement.spacedBy(gap)) {
                effects.forEach { effect ->
                    EffectTile(catalog, effect, width.value, { pick(effect) }, { detail(effect) }, effect.id in favourites, recommended = true)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DeskEffectsHome(
    scroll: androidx.compose.foundation.ScrollState, catalog: EffectCatalog, recommended: List<EffectDefinition>, recent: List<String>, favourites: Set<String>, presetFavourites: app.absentmotion.effects.PresetFavourites, favPage: Boolean, onFavPage: (Boolean) -> Unit,
    listed: List<Pair<String, String>>, creators: Map<String, List<EffectDefinition>>, pick: (EffectDefinition) -> Unit, addPreset: (EffectDefinition, EffectPreset) -> Unit, detail: (EffectDefinition) -> Unit, open: (String) -> Unit,
) {
    val shown: List<Pair<EffectDefinition, EffectPreset?>> = if (favPage) presetFavourites.resolve(catalog.definitions).map { it.first to it.second } + favourites.mapNotNull(catalog::find).map { it to null }
        else recent.mapNotNull(catalog::find).distinct().take(12).map { it to null }
    Column(Modifier.fillMaxSize().deskVerticalScroll(scroll).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RecommendedEffects(catalog, recommended, favourites, pick, detail)
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
                            EffectsCategoryTile(catalog, key, name, creators, Modifier.weight(1f).height(72.dp)) { open(key) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectsCategoryTile(
    catalog: EffectCatalog, key: String, name: String, creators: Map<String, List<EffectDefinition>>, modifier: Modifier, onClick: () -> Unit,
) {
    val slug = key.takeIf { it.startsWith(CreatorCategoryPrefix) }?.removePrefix(CreatorCategoryPrefix)
    val enabled = key != LocalBlockedCategory.current
    val press = remember { MutableInteractionSource() }
    var menu by remember(key) { mutableStateOf(false) }
    val hold = { menu = true }
    val style = if (deskLayout) Modifier.clip(RoundedCornerShape(8.dp)).alpha(if (enabled) 1f else 0.35f)
        .hoverFill(8.dp, Color.White.copy(alpha = .12f)).handCursor()
    else Modifier.pressScale(press, TilePress).alpha(if (enabled) 1f else 0.35f)
    val interaction = when {
        slug != null -> Modifier.rightClick(hold).combinedClickable(press, LocalIndication.current, onClick = onClick, onLongClick = hold)
        deskLayout -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier.clickable(press, LocalIndication.current, enabled = enabled, onClick = onClick)
    }
    Box(modifier.then(style).then(interaction), Alignment.Center) {
        when {
            slug != null -> Thumbnail(catalog, creators[slug]?.firstNotNullOfOrNull { it.addedPicture }, Modifier.fillMaxSize(), ContentScale.Crop)
            key == "ai" -> AiBackdrop(Modifier.fillMaxSize())
            key == "audio" -> AudioBackdrop(Modifier.fillMaxSize())
            else -> Thumbnail(catalog, catalog.listed(key).firstOrNull { !it.isAdded || key == AddedEffectCategory }?.thumbnail, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        if (deskLayout) {
            Text(name, Modifier.padding(horizontal = 8.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        } else {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        if (slug != null) {
            AddedThumbnailBadge()
            AddedCreatorMenu(slug, shown = true, expanded = menu) { menu = false }
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
private fun EffectTile(catalog: EffectCatalog, effect: EffectDefinition, width: Float, onClick: () -> Unit, onLongClick: () -> Unit, favourite: Boolean, preset: EffectPreset? = null, recommended: Boolean = false) {
    val press = remember { MutableInteractionSource() }
    val importer = LocalAddedEffectImport.current
    val scope = rememberCoroutineScope()
    var menu by remember(effect.id) { mutableStateOf(false) }
    val hold: () -> Unit = if (effect.isAdded && importer != null) ({ menu = true }) else onLongClick
    Column(Modifier.width(width.dp).pressScale(press, TilePress).alpha(if (effect.usable(LocalBlockedCategory.current)) 1f else 0.35f).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, Am.Field).handCursor()
        .rightClick(hold).combinedClickable(press, LocalIndication.current, onClick = onClick, onLongClick = hold).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Thumbnail(catalog, preset?.let(effect::thumbnail) ?: effect.thumbnail, Modifier.size((width - 7).dp).clip(RoundedCornerShape(5.dp)))
            if (effect.isAdded) {
                AddedThumbnailBadge()
                if (importer != null) Presence(menu) {
                    MenuPopup(DropOrRise, { menu = false }, TransformOrigin(0.5f, 0f)) {
                        val categoryName = catalog.categories.firstOrNull { it.first == effect.category }?.second
                        if (categoryName != null) {
                            val shown = catalog.isShownInCategory(effect)
                            val label = if (shown) R.string.added_effect_remove_from_category else R.string.added_effect_show_in_category
                            MenuRow(stringResource(label, categoryName), icon = if (shown) ChromeIcons.Minus else ChromeIcons.Plus) {
                                menu = false
                                scope.launch { importer.showInCategory(effect.id, !shown) }
                            }
                        }
                        MenuRow(stringResource(R.string.added_effect_remove), icon = ChromeIcons.Trash) {
                            menu = false
                            importer.removing = effect
                        }
                    }
                }
            }
            if (favourite) Icon(PanelIcons.StarFilled, null, Modifier.align(Alignment.TopEnd).size(14.dp), tint = Am.Favorite)
            if (preset == null && catalog.isNew(effect)) Text(stringResource(R.string.t_new), Modifier.align(Alignment.TopStart).padding(3.dp).clip(RoundedCornerShape(3.dp)).background(Am.Accent)
                .padding(horizontal = 3.dp), color = Am.OnAccent, fontSize = if (deskLayout) 12.sp else 7.sp, lineHeight = if (deskLayout) 14.sp else 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(preset?.name ?: effect.name, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDark, fontSize = if (deskLayout) 12.sp else if (recommended || width > 100) 10.sp else 8.sp,
            lineHeight = if (deskLayout) 14.sp else if (recommended) 12.sp else 9.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddedCreatorTile(catalog: EffectCatalog, slug: String, name: String, picture: String?, width: Float, onClick: () -> Unit) {
    var menu by remember(slug) { mutableStateOf(false) }
    val hold = { menu = true }
    Column(Modifier.width(width.dp).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, Am.Field).handCursor()
        .rightClick(hold).combinedClickable(onClick = onClick, onLongClick = hold).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size((width - 7).dp).clip(RoundedCornerShape(5.dp)).background(RecommendedBg), Alignment.Center) {
            if (picture != null) Thumbnail(catalog, picture, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(24.dp).clip(RoundedCornerShape(4.dp)).background(RecommendedBg),
                Alignment.Center,
            ) {
                Icon(app.absentmotion.ui.home.HomeIcons.Folder, null, Modifier.size(18.dp), tint = Am.OnDark)
            }
            AddedThumbnailBadge()
            AddedCreatorMenu(slug, slug in catalog.shownAddedCreators, menu) { menu = false }
        }
        Text(name, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDark, fontSize = if (deskLayout) 12.sp else 8.sp,
            lineHeight = if (deskLayout) 14.sp else 9.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddedCreatorMenu(slug: String, shown: Boolean, expanded: Boolean, dismiss: () -> Unit) {
    val importer = LocalAddedEffectImport.current ?: return
    val scope = rememberCoroutineScope()
    Presence(expanded) {
        MenuPopup(DropOrRise, dismiss, TransformOrigin(0.5f, 0f)) {
            val label = if (shown) R.string.added_effect_remove_creator_category else R.string.added_effect_show_creator_category
            MenuRow(stringResource(label), icon = if (shown) ChromeIcons.Minus else ChromeIcons.Plus) {
                dismiss()
                scope.launch { importer.showCreatorCategory(slug, !shown) }
            }
        }
    }
}

@Composable
private fun BoxScope.AddedThumbnailBadge() {
    AddedEffectBadge(Modifier.align(Alignment.BottomStart).padding(4.dp)
        .clip(RoundedCornerShape(4.dp)).background(RecommendedBg).padding(2.dp))
}

@Composable
internal fun AddedEffectBadge(modifier: Modifier = Modifier) {
    Icon(EffectIcons.Added, stringResource(R.string.added_effect_badge), modifier.size(16.dp), tint = Am.OnDark)
}

@Composable
private fun AddEffectTile(width: Float, onClick: () -> Unit) {
    Column(Modifier.width(width.dp).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, Am.Field).handCursor().clickable(onClick = onClick).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size((width - 7).dp).clip(RoundedCornerShape(5.dp)).background(RecommendedBg), Alignment.Center) {
            Text(stringResource(R.string.added_effect_card), Modifier.padding(5.dp), color = Am.OnDark, fontSize = if (deskLayout) 13.sp else 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AddedEffectsEmpty() {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.added_effect_empty_title), color = Am.OnDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.added_effect_empty_body), color = Am.OnDarkDim, fontSize = 14.sp)
        Text(stringResource(R.string.added_effect_safety), color = Am.OnDarkDim, fontSize = 14.sp)
        Text(stringResource(R.string.added_effect_guide), Modifier.handCursor().clickable {
            runCatching { links.openUri("https://github.com/Comett1225/AbsentMotion-Community/blob/main/effects/README.md") }
        }.padding(vertical = 8.dp), color = Am.Accent, fontSize = 14.sp)
    }
}

@Composable
private fun EffectHero(catalog: EffectCatalog, effect: EffectDefinition) {
    val big by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, catalog, effect.key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            catalog.bytes(effect.hero)?.let { runCatching { app.absentmotion.platform.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(minOf(maxWidth / 1.25f, 340.dp))) {
            Box(Modifier.matchParentSize().background(RecommendedBg)) {
                big?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: app.absentmotion.platform.EffectPicture(catalog, effect.thumbnail, ContentScale.Crop)
            }
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 0.8f to BrowserBg.copy(alpha = 0.72f), 1f to BrowserBg)))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(effect.name, color = Am.OnDark, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(effect.description, Modifier.padding(top = 4.dp), color = Am.OnDark, fontSize = 15.sp)
            }
        }
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
    if (deskLayout) return this
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
