package app.absentmotion.ui.editor.effects

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
import app.absentmotion.platform.asImageBitmap
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
import app.absentmotion.editor.EditorState
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.addEffect
import app.absentmotion.model.effects
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var category by remember { mutableStateOf<String?>(if (state.selected?.kind == "audio") "ai" else null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf(state.effectDetail?.let(catalog::find)) }
    fun close() { state.effectDetail = null; state.effectBrowserOpen = false }
    fun back() {
        when { detail != null -> if (state.effectDetail != null) close() else detail = null; searching -> { searching = false; query = "" }; category != null -> category = null; else -> close() }
    }
    BackHandler(onBack = ::back)
    fun favourite(effect: EffectDefinition) {
        favourites = if (effect.id in favourites) favourites - effect.id else favourites + effect.id
        history.edit().putStringSet("favourites", favourites).apply()
    }
    fun add(effect: EffectDefinition) {
        if (!effect.available) return
        state.editSelected { layer -> layer.addEffect(effect.instance()) }
        recent = (listOf(effect.id) + recent.filterNot { it == effect.id }).take(40)
        history.edit().putString("recent", recent.joinToString("|")).apply()
        state.effectIndex = (state.selected?.effects?.size ?: 0) - 1
        close()
    }
    fun pick(effect: EffectDefinition) { if (effect.available) add(effect) else detail = effect }
    Column(Modifier.fillMaxSize().background(BrowserBg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(BrowserBg), verticalAlignment = Alignment.CenterVertically) {
            BrowserButton(if (category != null || detail != null || searching) PanelIcons.Back else EffectIcons.Close, ::back)
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(query, { query = it }, Modifier.weight(1f).padding(8.dp).focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = Am.OnDark, fontSize = 17.sp), cursorBrush = SolidColor(Am.Accent))
            }
            else Text(detail?.name ?: category?.let { key -> catalog.categories.firstOrNull { it.first == key }?.second } ?: "Effects Library",
                Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 1)
            BrowserButton(EffectIcons.Search, { state.effectDetail = null; detail = null; searching = true })
        }
        Swap(listOf(detail?.id, searching, category), Modifier.fillMaxSize()) {
        if (catalog.message != null && (app.absentmotion.platform.effectsMessageOnHome() || (category != null && category != "ai" && !searching))) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(catalog.message!!, color = Am.OnDark, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else if (detail != null) {
            val effect = detail!!
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Thumbnail(catalog, effect.thumbnail, Modifier.align(Alignment.CenterHorizontally).size(160.dp))
                Text(effect.description, Modifier.padding(vertical = 16.dp), color = Am.OnDark, fontSize = 14.sp)
                if (state.effectDetail == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("+ Standard Settings", Modifier.weight(1f).height(48.dp).alpha(if (effect.available) 1f else 0.35f)
                        .clickable(enabled = effect.available) { add(effect) }.padding(12.dp), color = Am.OnDark, fontSize = 15.sp)
                    Icon(PanelIcons.Star, "Favorite", Modifier.size(32.dp).clickable { favourite(effect) }, tint = if (effect.id in favourites) Am.Accent else Am.OnDark)
                }
            }
        } else if (searching || category != null) {
            val results = catalog.listed(if (searching) null else category).filter { query.isBlank() || it.name.contains(query, true) }
                .sortedBy { it.name.lowercase() }
            val actions = if (searching || category == "ai") AiAction.entries.filter { it.offered && (query.isBlank() || it.title.contains(query, true)) } else emptyList()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                items(actions, key = { "ai-${it.name}" }) { action -> AiTile(action, state.selected, ai) }
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
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)) {
                        Icon(EffectIcons.Recent, "Recent", Modifier.size(12.dp).clickable { favPage = false }, tint = if (!favPage) Am.Accent else Am.OnDarkDim)
                        Icon(PanelIcons.Star, "Favorite", Modifier.size(12.dp).clickable { favPage = true }, tint = if (favPage) Am.Accent else Am.OnDarkDim)
                    }
                }
                catalog.categories.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(1.5.dp)) {
                        row.forEach { (key, name) ->
                            Box(Modifier.weight(1f).height(66.5.dp).padding(bottom = 1.5.dp).clickable { category = key }, contentAlignment = Alignment.Center) {
                                if (key == "ai") AiBackdrop(Modifier.fillMaxSize())
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
    AiProgressDialog(ai)
}

private val BrowserBg get() = Am.Field
private val RecommendedBg get() = Am.PanelInset

@Composable
private fun BrowserButton(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = Am.OnDark)
    }
}

@Composable
private fun EffectTile(catalog: EffectCatalog, effect: EffectDefinition, width: Float, onClick: () -> Unit, onLongClick: () -> Unit, favourite: Boolean) {
    Column(Modifier.width(width.dp).alpha(if (effect.available) 1f else 0.35f).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Thumbnail(catalog, effect.thumbnail, Modifier.size((width - 7).dp).clip(RoundedCornerShape(5.dp)))
            if (favourite) Icon(PanelIcons.Star, null, Modifier.align(Alignment.TopEnd).size(14.dp), tint = Am.Accent)
            if (catalog.isNew(effect)) Text("NEW", Modifier.align(Alignment.TopStart).padding(3.dp).clip(RoundedCornerShape(3.dp)).background(Am.Accent)
                .padding(horizontal = 3.dp), color = Color.White, fontSize = 7.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(effect.name, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDark, fontSize = if (width > 100) 10.sp else 8.sp,
            lineHeight = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun Thumbnail(catalog: EffectCatalog, path: String?, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    val bitmap by produceState<app.absentmotion.platform.Bitmap?>(null, catalog, path) {
        value = withContext(Dispatchers.IO) { catalog.image(path) }
    }
    Box(modifier.background(RecommendedBg)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = scale) }
    }
}
