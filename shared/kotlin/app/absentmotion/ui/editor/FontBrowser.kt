package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialGuard

import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.presenceScrim
import app.absentmotion.ui.theme.withUiFont
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.platform.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.provider.OpenableColumns
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import app.absentmotion.fonts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.HoldName
import app.absentmotion.ui.home.swipeOpens
import app.absentmotion.ui.home.DialogPill

private val BrowserBg get() = Am.PanelInset
private val DrawerBg get() = Am.Panel
private val BrowserLine get() = Am.Line

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FontBrowser(library: FontLibrary, onSelect: (String) -> Unit, onClose: () -> Unit, googleList: (suspend () -> List<GoogleFamily>)? = null, deviceList: List<FontName>? = null) {
    val context = LocalContext.current
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    var drawer by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf(library.browserCategories) }
    var sorting by remember { mutableStateOf(library.browserSorting) }
    var language by remember { mutableIntStateOf(library.browserLanguage) }
    var languageMenu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val downloads by library.revision.collectAsState()
    var error by remember { mutableStateOf<FontError?>(null) }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(FontSource.All) }
    var google by remember { mutableStateOf(emptyList<GoogleFamily>()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val searching = !search.isNullOrBlank()
    LaunchedEffect(searching) { if (searching && google.isEmpty()) google = (googleList ?: library::googleFonts)() }
    val device by produceState(emptyList<FontName>(), deviceList) { value = deviceList ?: withContext(Dispatchers.IO) { library.deviceFonts() } }
    LaunchedEffect(search != null) { if (search != null) { searchFocus.requestFocus(); keyboard?.show() } else keyboard?.hide() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val failures = mutableListOf<String>()
            var imported = 0
            uris.forEach { uri ->
                val result = try { library.importAll(uri) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { FontLibrary.Imported(emptyList(), listOf((uri.lastPathSegment ?: "") to FontProblem.Corrupted)) } catch (_: OutOfMemoryError) { FontLibrary.Imported(emptyList(), listOf((uri.lastPathSegment ?: "") to FontProblem.TooLarge)) }
                imported += result.fonts.size
                result.failures.forEach { (name, problem) -> failures += app.absentmotion.i18n.AppText.get(R.string.font_import_reason_line, name, app.absentmotion.i18n.AppText.get(FontReasons.getValue(problem))) }
            }
            if (failures.isNotEmpty()) error = FontError(if (imported == 0) R.string.font_import_failed_title else R.string.font_import_some_failed_title, R.string.font_import_reasons_body, failures.joinToString("\n"))
            revision++
        }
    }
    val choose = { name: String ->
        if (!loading) scope.launch {
            loading = true
            val file = library.resolve(name, download = true)
            loading = false
            if (file != null || FontName.parse(name).source != "googlefonts") onSelect(name)
            else error = FontError(R.string.font_load_failed_title, R.string.font_load_failed_body)
        }
        Unit
    }
    LaunchedEffect(categories, sorting, language) { library.browserCategories = categories; library.browserSorting = sorting; library.browserLanguage = language }
    val title = when {
        categories.isEmpty() || categories.containsAll(AllCategories) ->
            LanguageLabels[language]?.takeIf { language in TitledLanguages }?.let { stringResource(R.string.font_title_language, stringResource(it)) } ?: stringResource(R.string.font_title_all)
        language == 0 && categories.size == 1 -> stringResource(R.string.font_title_category, stringResource(CategoryLabels.getValue(categories.single())))
        else -> stringResource(R.string.font_title_filtered)
    }
    val closeSearch = { search = null; source = FontSource.All }
    BackHandler { when { drawer -> drawer = false; search != null -> closeSearch(); else -> onClose() } }
    val favourites = remember(revision) { library.favourites.toSet() }
    val results = remember(revision, categories, sorting, language, search, source, google, device) {
        fun kept(all: List<FontEntry>) = all.filter { entry ->
            val matchesCategory = categories.isEmpty() || entry.category in categories || (110 in categories && entry.name.xml in favourites)
            matchesCategory && (language == 0 || language in entry.languages || entry.category == 100)
        }.let { filtered -> if (sorting == "Alphabetical") filtered.sortedBy { it.name.label.lowercase() } else filtered }
        val realCategories = categories.filter { it in 1..5 }
        GoogleFonts.arrange(kept(library.importedFonts().map { FontEntry(it, 100, emptySet()) }), kept(FontCatalog.entries),
            if (searching || source == FontSource.Device) device.map { FontEntry(it, 0, emptySet()) } else emptyList(),
            google.filter { realCategories.isEmpty() || it.category in realCategories }, search.orEmpty(), source)
    }
    val entries = results.local
    val familyChosen = { family: GoogleFamily ->
        if (downloading == null) scope.launch {
            val name = family.regular.xml
            downloading = family.family
            val file = library.resolve(name, download = true)
            downloading = null
            if (file != null) onSelect(name) else error = FontError(R.string.font_load_failed_title, R.string.font_load_failed_body)
        }
        Unit
    }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().background(BrowserBg).swipeOpens(!drawer && !loading, rtl, anywhere = true) { drawer = true }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(42.dp).border(0.5.dp, BrowserLine), verticalAlignment = Alignment.CenterVertically) {
                FontIconButton(TextIcons.Menu, stringResource(R.string.font_menu)) { drawer = true }
                if (search == null) Text(title, Modifier.weight(1f), color = Am.OnDark, fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                else BasicTextField(search.orEmpty(), { search = it }, Modifier.weight(1f).focusRequester(searchFocus), textStyle = TextStyle(color = Am.Gold, fontSize = 16.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content).withUiFont(),
                    singleLine = true, decorationBox = { inner -> if (search.isNullOrEmpty()) Text(stringResource(R.string.type_here_to_search), color = Am.OnDark.copy(alpha = .3f), fontSize = 16.sp); inner() })
                FontIconButton(if (search == null) TextIcons.Search else TextIcons.Tick, if (search == null) stringResource(R.string.font_search) else stringResource(R.string.done)) {
                    if (search == null) search = "" else closeSearch()
                }
            }
            if (search != null) Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FontSource.entries.forEach { option ->
                    Box(Modifier.defaultMinSize(minHeight = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (option == source) Am.Accent else Am.Field).clickable { source = option }.padding(horizontal = 14.dp, vertical = 6.dp), Alignment.Center) {
                        Text(stringResource(SourceLabels.getValue(option)), color = if (option == source) Am.OnAccent else Am.OnDark, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            LazyVerticalGrid(if (app.absentmotion.ui.adaptive.LocalAdaptiveSpace.current?.wide == true) GridCells.Adaptive(180.dp) else GridCells.Fixed(2), Modifier.weight(1f)) {
                items(entries, key = { it.name.xml }) { entry ->
                    val needsDownload by produceState(false, entry.name.xml, downloads) {
                        value = !entry.bundled && entry.category != 100 && withContext(Dispatchers.IO) { library.localFile(entry.name.xml) == null }
                    }
                    Box(Modifier.fillMaxWidth().height(100.dp).border(.5.dp, BrowserLine).rightClick { if (entry.category == 100) deleting = entry }.combinedClickable(onLongClick = { if (entry.category == 100) deleting = entry }) { choose(entry.name.xml) }) {
                        FontPreview(library, entry.name.xml, "Abcdefg", Modifier.align(Alignment.TopCenter).padding(top = 13.dp, start = 10.dp, end = 10.dp).fillMaxWidth().height(46.dp))
                        Text(entry.name.label, Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 25.dp, bottom = 13.dp),
                            color = Am.OnDark, fontSize = 12.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (entry.category == 100) Icon(TextIcons.Import, stringResource(R.string.font_imported), Modifier.padding(8.dp).size(13.dp), tint = Am.OnDark.copy(alpha = .38f))
                        if (needsDownload) Icon(TextIcons.Cloud, stringResource(R.string.font_downloads_when_chosen), Modifier.align(Alignment.TopEnd).padding(8.dp).size(15.dp), tint = Am.OnDark.copy(alpha = .38f))
                        FavouriteStar(entry.name.xml in favourites, { library.favourite(entry.name.xml); revision++ }, stringResource(R.string.font_favorite), Modifier.align(Alignment.BottomEnd).size(34.dp),
                            starSize = 20.dp, offTint = Am.OnDark.copy(alpha = .38f), holdName = entry.category != 100)
                    }
                }
                if (results.google.isNotEmpty() && source == FontSource.All) item(key = "google-divider", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = Am.Caption.copy(alpha = .4f))
                        Text(stringResource(R.string.font_source_google), Modifier.padding(horizontal = 10.dp), color = Am.Caption, fontSize = 12.sp, maxLines = 1)
                        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = Am.Caption.copy(alpha = .4f))
                    }
                }
                items(results.google, key = { "google:" + it.family }, span = { GridItemSpan(maxLineSpan) }) { family ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).border(.5.dp, BrowserLine).clickable { familyChosen(family) }.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(family.family, color = Am.OnDark, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = TextStyle().withUiFont())
                            CategoryLabels[family.category]?.let { Text(stringResource(it), color = Am.Caption, fontSize = 11.sp, maxLines = 1) }
                        }
                        Box(Modifier.size(34.dp), Alignment.Center) {
                            if (downloading == family.family) CircularProgressIndicator(Modifier.size(18.dp), color = Am.Gold, strokeWidth = 2.dp)
                            else Icon(app.absentmotion.ui.home.HomeIcons.Download, stringResource(R.string.font_downloads_when_chosen), Modifier.size(18.dp), tint = Am.OnDark.copy(alpha = .38f))
                        }
                    }
                }
                if (source == FontSource.Google && !searching) item(key = "google-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.font_google_hint), Modifier.fillMaxWidth().padding(16.dp), color = Am.Caption, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                item(key = "dafont", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().clickable { runCatching { links.openUri(daFontUrl(search)) } }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.dafont_find), color = Am.Gold, fontSize = 15.sp)
                        Text(stringResource(R.string.dafont_hint), Modifier.padding(top = 4.dp), color = Am.Caption, fontSize = 12.sp)
                    }
                }
            }
        }
        Presence(drawer) { Box(Modifier.fillMaxSize().swipeOpens(drawer, !rtl, anywhere = true) { drawer = false }) {
            Box(Modifier.fillMaxSize().presenceScrim(Color.Black.copy(alpha = .6f)).clickable { drawer = false })
            Column(Modifier.presence(fade = false, slideX = -1f).width(280.dp).fillMaxHeight().background(DrawerBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                listOf(2, 1, 3, 4, 5, 110, 100).forEach { category ->
                    Row(Modifier.fillMaxWidth().height(46.dp).clip(DrawerShape).clickable { categories = if (category in categories) categories - category else categories + category }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(category in categories, null, colors = CheckboxDefaults.colors(checkedColor = Am.Accent, uncheckedColor = Am.Caption, checkmarkColor = Am.OnAccent))
                        Text(stringResource(CategoryLabels.getValue(category)), Modifier.padding(start = 14.dp), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = BrowserLine)
                DrawerLabel(stringResource(R.string.sorting))
                Row(Modifier.fillMaxWidth().height(44.dp).background(Am.Field, DrawerShape).padding(3.dp)) {
                    SortingLabels.forEach { (option, label) ->
                        val chosen = option == (sorting.takeIf { it in SortingLabels } ?: "Popular")
                        Box(Modifier.weight(1f).fillMaxHeight().clip(DrawerShape).background(if (chosen) Am.Accent else Color.Transparent).clickable { sorting = option }.padding(horizontal = 6.dp), Alignment.Center) {
                            Text(stringResource(label), color = if (chosen) Am.OnAccent else Am.OnDark, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                DrawerLabel(stringResource(R.string.languages))
                Box {
                    Row(Modifier.fillMaxWidth().height(44.dp).clip(DrawerShape).background(Am.Field).clickable { languageMenu = true }.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(LanguageLabels[language] ?: R.string.all_languages), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Icon(ChromeIcons.ChevronDown, null, Modifier.size(20.dp), tint = Am.OnDark)
                    }
                    Presence(languageMenu) {
                        MenuPopup(DropOrRise, { languageMenu = false }) {
                            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                MenuRow(stringResource(R.string.all_languages), trailing = if (language == 0) PanelIcons.Check else null) { language = 0; languageMenu = false }
                                FontCatalog.languages.keys.map { it to stringResource(LanguageLabels.getValue(it)) }.sortedBy { it.second }.forEach { (id, label) ->
                                    MenuRow(label, trailing = if (language == id) PanelIcons.Check else null) { language = id; languageMenu = false }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = BrowserLine)
                Row(Modifier.fillMaxWidth().height(44.dp).clip(DrawerShape).background(Am.Button).clickable { importer.launch(arrayOf("*/*")) }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(TextIcons.Import, null, Modifier.size(20.dp), tint = Am.OnDark)
                    Text(stringResource(R.string.import_fonts), Modifier.padding(start = 10.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
                }
            }
        } }
        if (loading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)).clickable(interactionSource = null, indication = null) {}, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Am.Gold); Text(stringResource(R.string.loading_font), color = Color.White, fontSize = 16.sp) }
        }
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.remove_this_imported_font)) }, text = { Text(entry.name.label) },
        confirmButton = { DialogPill(stringResource(R.string.delete_font)) { library.deleteImported(entry.name.xml); deleting = null; revision++ } },
        dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false, editor = true) { deleting = null } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark) }
    error?.let { failure -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(failure.title)) }, text = { Text(stringResource(failure.body, failure.detail)) }, confirmButton = { DialogPill(stringResource(R.string.ok)) { error = null } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark) }
}

private val DrawerShape = RoundedCornerShape(8.dp)

@Composable
private fun DrawerLabel(text: String) {
    Text(text, Modifier.padding(start = 2.dp, bottom = 8.dp), color = Am.Caption, fontSize = 12.sp, maxLines = 1)
}

private class FontError(@StringRes val title: Int, @StringRes val body: Int, val detail: String = "")

private val FontReasons = mapOf(
    FontProblem.NotFont to R.string.font_reason_not_font, FontProblem.Unsupported to R.string.font_reason_unsupported,
    FontProblem.TooLarge to R.string.font_reason_too_large, FontProblem.AlreadyImported to R.string.font_reason_duplicate,
    FontProblem.Corrupted to R.string.font_reason_corrupted, FontProblem.Unreadable to R.string.font_reason_unreadable,
    FontProblem.NoFonts to R.string.font_reason_zip_empty, FontProblem.ZipTooBig to R.string.font_reason_zip_too_big,
)

private val AllCategories = setOf(1, 2, 3, 4, 5, 100, 110)
private val TitledLanguages = setOf(22, 12, 16, 7, 1, 31, 20, 17)

private val CategoryLabels = mapOf(
    1 to R.string.font_category_sans_serif, 2 to R.string.font_category_serif, 3 to R.string.font_category_display,
    4 to R.string.font_category_handwriting, 5 to R.string.font_category_monospace, 110 to R.string.font_favorite, 100 to R.string.font_imported,
)

private val SourceLabels = mapOf(FontSource.All to R.string.font_source_all, FontSource.Imported to R.string.font_imported, FontSource.App to R.string.font_source_app,
    FontSource.Device to R.string.font_source_device, FontSource.Google to R.string.font_source_google)

private val SortingLabels = linkedMapOf("Popular" to R.string.font_sort_popular, "Alphabetical" to R.string.font_sort_alphabetical)

private val LanguageLabels = mapOf(
    1 to R.string.font_language_arabic, 3 to R.string.font_language_bengali, 5 to R.string.font_language_chinese_simplified,
    6 to R.string.font_language_chinese_traditional, 7 to R.string.font_language_cyrillic, 8 to R.string.font_language_cyrillic_extended,
    9 to R.string.font_language_devanagari, 12 to R.string.font_language_greek, 13 to R.string.font_language_greek_extended,
    16 to R.string.font_language_hebrew, 17 to R.string.font_language_japanese, 20 to R.string.font_language_korean,
    22 to R.string.font_language_latin, 23 to R.string.font_language_latin_extended, 31 to R.string.font_language_thai,
    33 to R.string.font_language_vietnamese,
)

@Composable
internal fun FontIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Am.OnDark, onClick: () -> Unit) {
    Box(modifier.size(42.dp).tutorialGuard().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = tint)
        HoldName(label)
    }
}

@Composable
internal fun rememberFontFamily(library: FontLibrary, name: String, download: Boolean = true): FontFamily? {
    val downloads by library.revision.collectAsState()
    return produceState(library.cachedTypeface(name)?.let { FontFamily(it) }, name, downloads) {
        if (download) library.resolve(name)
        value = withContext(Dispatchers.IO) { if (library.renderFile(name) != null) app.absentmotion.platform.fontFamily(library, name) else null }
    }.value
}

@Composable
internal fun FontPreview(library: FontLibrary, name: String, content: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(content, fontFamily = rememberFontFamily(library, name, download = false), color = Am.OnDark, fontSize = 20.sp, maxLines = 1, softWrap = false,
            style = TextStyle(platformStyle = app.absentmotion.platform.fontPreviewStyle(), textDirection = androidx.compose.ui.text.style.TextDirection.Content))
    }
}
