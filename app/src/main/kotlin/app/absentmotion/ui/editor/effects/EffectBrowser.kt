package app.absentmotion.ui.editor.effects

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import app.absentmotion.platform.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiTools
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
import app.absentmotion.model.addEffect
import app.absentmotion.model.effects
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.matchesSearch
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
fun EffectBrowser(state: EditorState) {
    val catalog by state.engine.effects.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember(state, context, scope) { AiTools(context.applicationContext, state, scope) }
    val history = remember { context.getSharedPreferences("effects", 0) }
    var recent by remember { mutableStateOf(history.getString("recent", "")!!.split('|').filter { it.isNotEmpty() }) }
    var favourites by remember { mutableStateOf(history.getStringSet("favourites", emptySet())!!.toSet()) }
    var favPage by remember { mutableStateOf(false) }
    val layer = state.selected
    val audio = layer?.kind == "audio"
    val hasSound = audio || layer?.mediaFill?.video == true
    var category by remember { mutableStateOf<String?>(if (audio) state.effectCategory ?: "audio" else null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf(state.effectDetail?.let(catalog::find)) }
    fun close() { state.effectDetail = null; state.effectCategory = null; state.effectBrowserOpen = false }
    var fromAudio by remember { mutableStateOf(false) }
    fun back() {
        when {
            detail != null -> if (state.effectDetail != null) close() else detail = null
            searching -> { searching = false; query = "" }
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
    Column(Modifier.fillMaxSize().background(BrowserBg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(BrowserBg), verticalAlignment = Alignment.CenterVertically) {
            val stepping = (category != null && !audio) || detail != null || searching || fromAudio
            BrowserButton(if (stepping) PanelIcons.Back else EffectIcons.Close, stringResource(if (stepping) R.string.topbar_back else R.string.action_close), ::back)
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(query, { query = it }, Modifier.weight(1f).padding(8.dp).focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = Am.OnDark, fontSize = 17.sp), cursorBrush = SolidColor(Am.Accent))
            }
            else Text(detail?.name ?: category?.let { key -> when (key) { "audio" -> audioName; "voice" -> voiceName; else -> catalog.categories.firstOrNull { it.first == key }?.second } } ?: stringResource(R.string.effectbrowser_title),
                Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 1)
            if (audio) Spacer(Modifier.size(44.dp))
            else BrowserButton(EffectIcons.Search, stringResource(R.string.home_search), { state.effectDetail = null; detail = null; searching = true })
        }
        Swap(listOf(detail?.id, searching, category), Modifier.fillMaxSize()) {
        if (catalog.message != null && !audio && (app.absentmotion.platform.effectsMessageOnHome() || (category != null && category != "ai" && category != "audio" && category != "voice" && !searching))) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(catalog.message!!, color = Am.OnDark, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else if (detail != null) {
            val effect = detail!!
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Thumbnail(catalog, effect.thumbnail, Modifier.align(Alignment.CenterHorizontally).size(160.dp))
                Text(effect.description, Modifier.padding(vertical = 16.dp), color = Am.OnDark, fontSize = 14.sp)
                if (state.effectDetail == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.standard_settings), Modifier.weight(1f).height(48.dp).alpha(if (effect.available) 1f else 0.35f)
                        .clickable(enabled = effect.available) { add(effect) }.padding(12.dp), color = Am.OnDark, fontSize = 15.sp)
                    Icon(PanelIcons.Star, stringResource(R.string.effectbrowser_favorite), Modifier.size(32.dp).clickable { favourite(effect) }, tint = if (effect.id in favourites) Am.Accent else Am.OnDark)
                }
                if (state.effectDetail == null) effect.presets.forEach { preset ->
                    Column(Modifier.fillMaxWidth().height(56.dp).alpha(if (effect.available) 1f else 0.35f)
                        .clickable(enabled = effect.available) { add(effect, preset) }.padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.effectbrowser_preset), color = Am.OnDarkDim, fontSize = 10.sp, maxLines = 1)
                        Text("+ ${preset.name}", color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        } else if (!searching && (category == "audio" || category == "voice")) {
            val effects = layer?.audioEffects.orEmpty()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                if (category == "audio") itemsIndexed(AudioOrder) { i, kind ->
                    AudioTile(stringResource(kind.label), i, kind == FxKind.Voice || effects.fits(kind)) {
                        if (kind == FxKind.Voice) { fromAudio = true; category = "voice" } else addAudio(kind)
                    }
                }
                else itemsIndexed(VoicePresets) { i, preset -> AudioTile(stringResource(preset.name), i, effects.any { it.kind == FxKind.Voice } || effects.fits(FxKind.Voice)) { applyVoice(preset) } }
            }
        } else if (searching || category != null) {
            val results = if (audio) emptyList() else if (searching) catalog.search(query) else catalog.listed(category).sortedBy { it.name.lowercase() }
            val actions = if (searching || category == "ai") AiAction.entries.filter { it.offered && matchesSearch(query, stringResource(it.title)) } else emptyList()
            val presets = if (audio) emptyList() else catalog.definitions.flatMap { d -> d.presets.map { d to it } }
                .filter { (d, p) -> if (searching) query.isNotBlank() && matchesSearch(query, p.name) else d.category == category }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                items(actions, key = { "ai-${it.name}" }) { action -> AiTile(action, state.selected, ai) }
                items(presets, key = { (d, p) -> "preset-${d.id}-${p.slug}" }) { (d, p) -> PresetTile(catalog, d, p) { add(d, p) } }
                items(results, key = { it.id }) { effect -> EffectTile(catalog, effect, 100f, { pick(effect) }, { detail = effect }, effect.id in favourites) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val recommended = listOf("absent.chromakey", "absent.blur", "absent.glow").mapNotNull(catalog::find)
                Row(Modifier.fillMaxWidth().height(186.dp).background(RecommendedBg).horizontalScroll(rememberScrollState()).padding(start = 7.5.dp, top = 16.dp), Arrangement.spacedBy(15.dp)) {
                    recommended.forEach { effect -> EffectTile(catalog, effect, 121f, { pick(effect) }, { detail = effect }, effect.id in favourites) }
                }
                Box(Modifier.fillMaxWidth().height(210.dp)) {
                    val ids = if (favPage) favourites.toList() else recent
                    val shown = ids.mapNotNull(catalog::find).distinct().take(10)
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                        repeat(2) { row ->
                            Row(Modifier.fillMaxWidth().height(100.dp)) {
                                repeat(5) { col ->
                                    Box(Modifier.weight(1f)) {
                                        shown.getOrNull(row * 5 + col)?.let { effect -> EffectTile(catalog, effect, 62f, { pick(effect) }, { detail = effect }, effect.id in favourites) }
                                    }
                                }
                            }
                        }
                    }
                    if (shown.isEmpty() && !favPage) Text(stringResource(R.string.effectbrowser_recent_empty), Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Row(Modifier.align(Alignment.BottomCenter)) {
                        Box(Modifier.size(40.dp, 22.dp).clickable { favPage = false }, Alignment.CenterEnd) {
                            Icon(EffectIcons.Recent, stringResource(R.string.effectbrowser_recent), Modifier.size(12.dp), tint = if (!favPage) Am.Accent else Am.OnDarkDim)
                        }
                        Box(Modifier.size(40.dp, 22.dp).clickable { favPage = true }, Alignment.CenterStart) {
                            Icon(PanelIcons.Star, stringResource(R.string.effectbrowser_favorite), Modifier.size(12.dp), tint = if (favPage) Am.Accent else Am.OnDarkDim)
                        }
                    }
                }
                val listed = catalog.categories.let { all -> if (hasSound) all.filter { it.first != "ai" } + ("audio" to audioName) + all.filter { it.first == "ai" } else all }
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
    AiProgressDialog(ai)
}

internal val AudioOrder = listOf(FxKind.Reverb, FxKind.HighPitch, FxKind.LowPitch, FxKind.Echo, FxKind.Voice, FxKind.Eq, FxKind.LowPass, FxKind.HighPass, FxKind.Compressor, FxKind.Gate)

private val BrowserBg get() = Am.Field
private val RecommendedBg get() = Am.PanelInset

@Composable
private fun BrowserButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark)
    }
}

@Composable
private fun EffectTile(catalog: EffectCatalog, effect: EffectDefinition, width: Float, onClick: () -> Unit, onLongClick: () -> Unit, favourite: Boolean) {
    val press = remember { MutableInteractionSource() }
    Column(Modifier.width(width.dp).pressScale(press, TilePress).alpha(if (effect.available) 1f else 0.35f)
        .combinedClickable(press, LocalIndication.current, onClick = onClick, onLongClick = onLongClick).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Thumbnail(catalog, effect.thumbnail, Modifier.size((width - 7).dp).clip(RoundedCornerShape(5.dp)))
            if (favourite) Icon(PanelIcons.Star, null, Modifier.align(Alignment.TopEnd).size(14.dp), tint = Am.Accent)
            if (catalog.isNew(effect)) Text(stringResource(R.string.t_new), Modifier.align(Alignment.TopStart).padding(3.dp).clip(RoundedCornerShape(3.dp)).background(Am.Accent)
                .padding(horizontal = 3.dp), color = Color.White, fontSize = 7.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(effect.name, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDark, fontSize = if (width > 100) 10.sp else 8.sp,
            lineHeight = 9.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun PresetTile(catalog: EffectCatalog, effect: EffectDefinition, preset: EffectPreset, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(Modifier.width(100.dp).pressScale(press, TilePress).alpha(if (effect.available) 1f else 0.35f)
        .clickable(press, LocalIndication.current, enabled = effect.available, onClick = onClick).padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Thumbnail(catalog, effect.thumbnail(preset), Modifier.size(93.dp).clip(RoundedCornerShape(5.dp)))
        Text(preset.name, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.Accent, fontSize = 10.sp, lineHeight = 9.sp,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun Thumbnail(catalog: EffectCatalog, path: String?, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    val drawable by produceState<Drawable?>(null, catalog, path) {
        value = withContext(Dispatchers.IO) { catalog.drawable(path) }
    }
    DisposableEffect(drawable) {
        val shown = drawable
        onDispose { (shown as? AnimatedImageDrawable)?.stop() }
    }
    Box(modifier.background(RecommendedBg)) {
        when (val d = drawable) {
            is AnimatedImageDrawable -> AndroidView({ ImageView(it) }, Modifier.fillMaxSize().clipToBounds(), update = { v ->
                v.scaleType = if (scale == ContentScale.Crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                if (v.drawable !== d) {
                    v.setImageDrawable(d)
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
            })
            is BitmapDrawable -> Image(d.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = scale)
            else -> {}
        }
    }
}
