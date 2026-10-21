package app.absentmotion.ui.editor

import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.presenceScrim
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.platform.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private val BrowserBg get() = Am.PanelInset
private val DrawerBg get() = Am.Panel
private val BrowserLine get() = Am.Line
private val Favourite get() = Am.Gold

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FontBrowser(library: FontLibrary, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var drawer by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf(library.browserCategories) }
    var sorting by remember { mutableStateOf(library.browserSorting) }
    var language by remember { mutableIntStateOf(library.browserLanguage) }
    var languageMenu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val downloads by library.revision.collectAsState()
    var error by remember { mutableStateOf<FontError?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(search != null) { if (search != null) { searchFocus.requestFocus(); keyboard?.show() } else keyboard?.hide() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val failures = mutableListOf<String>()
            uris.forEach { uri -> runCatching { library.import(uri) }.onFailure { failures.add(runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment ?: "") } }
            if (failures.isNotEmpty()) error = FontError(R.string.font_import_failed_title, R.string.font_import_failed_body, failures.joinToString("\n"))
            revision++
        }
    }
    val choose = { name: String ->
        if (!loading) scope.launch {
            loading = true
            val file = library.resolve(name)
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
    BackHandler { when { drawer -> drawer = false; search != null -> search = null; else -> onClose() } }
    val favourites = remember(revision) { library.favourites.toSet() }
    val entries = remember(revision, categories, sorting, language, search) {
        val all = library.importedFonts().map { FontEntry(it, 100, emptySet()) } + FontCatalog.entries
        val filtered = all.filter { entry ->
            val matchesCategory = categories.isEmpty() || entry.category in categories || (110 in categories && entry.name.xml in favourites)
            matchesCategory && (language == 0 || language in entry.languages || entry.category == 100) &&
                matchesSearch(search.orEmpty(), entry.name.label)
        }
        when (sorting) { "Alphabetical" -> filtered.sortedBy { it.name.label.lowercase() }
            else -> filtered }
    }
    Box(Modifier.fillMaxSize().background(BrowserBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(42.dp).border(0.5.dp, BrowserLine), verticalAlignment = Alignment.CenterVertically) {
                FontIconButton(TextIcons.Menu, stringResource(R.string.font_menu)) { drawer = true }
                if (search == null) Text(title, Modifier.weight(1f), color = Am.OnDark, fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                else BasicTextField(search.orEmpty(), { search = it }, Modifier.weight(1f).focusRequester(searchFocus), textStyle = TextStyle(color = Favourite, fontSize = 16.sp),
                    singleLine = true, decorationBox = { inner -> if (search.isNullOrEmpty()) Text(stringResource(R.string.type_here_to_search), color = Am.OnDark.copy(alpha = .3f), fontSize = 16.sp); inner() })
                FontIconButton(if (search == null) TextIcons.Search else TextIcons.Tick, if (search == null) stringResource(R.string.font_search) else stringResource(R.string.done)) {
                    search = if (search == null) "" else null
                }
            }
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f)) {
                items(entries, key = { it.name.xml }) { entry ->
                    val needsDownload by produceState(false, entry.name.xml, downloads) {
                        value = !entry.bundled && entry.category != 100 && withContext(Dispatchers.IO) { library.localFile(entry.name.xml) == null }
                    }
                    Box(Modifier.fillMaxWidth().height(100.dp).border(.5.dp, BrowserLine).combinedClickable(onLongClick = { if (entry.category == 100) deleting = entry }) { choose(entry.name.xml) }) {
                        FontPreview(library, entry.name.xml, "Abcdefg", Modifier.align(Alignment.TopCenter).padding(top = 13.dp, start = 10.dp, end = 10.dp).fillMaxWidth().height(46.dp))
                        Text(entry.name.label, Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 25.dp, bottom = 13.dp),
                            color = Am.OnDark, fontSize = 12.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (entry.category == 100) Icon(TextIcons.Import, stringResource(R.string.font_imported), Modifier.padding(8.dp).size(13.dp), tint = Am.OnDark.copy(alpha = .38f))
                        if (needsDownload) Icon(TextIcons.Cloud, stringResource(R.string.font_downloads_when_chosen), Modifier.align(Alignment.TopEnd).padding(8.dp).size(15.dp), tint = Am.OnDark.copy(alpha = .38f))
                        Icon(if (entry.name.xml in favourites) TextIcons.FilledStar else TextIcons.Star, stringResource(R.string.font_favorite), Modifier.align(Alignment.BottomEnd).size(34.dp).clickable {
                            library.favourite(entry.name.xml); revision++
                        }.padding(7.dp), tint = if (entry.name.xml in favourites) Favourite else Am.OnDark.copy(alpha = .38f))
                    }
                }
            }
        }
        Presence(drawer) {
            Box(Modifier.fillMaxSize().presenceScrim(Color.Black.copy(alpha = .6f)).clickable { drawer = false })
            Column(Modifier.presence(fade = false, slideX = -1f).width(280.dp).fillMaxHeight().background(DrawerBg).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                listOf(2, 1, 3, 4, 5, 110, 100).forEach { category ->
                    Row(Modifier.fillMaxWidth().height(40.dp).clickable { categories = if (category in categories) categories - category else categories + category },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(category in categories, null, colors = CheckboxDefaults.colors(checkedColor = Favourite, uncheckedColor = Am.OnDark))
                        Text(stringResource(CategoryLabels.getValue(category)), color = Am.OnDark, fontSize = 16.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Am.OnDark)
                Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sorting), Modifier.width(79.dp), color = Am.OnDark, fontSize = 14.sp)
                    Box {
                        Text(stringResource(SortingLabels[sorting] ?: R.string.font_sort_popular) + "  ˅", Modifier.width(181.dp).background(Am.Well).padding(12.dp).clickable { sortMenu = true }, color = Am.OnDark, fontSize = 16.sp)
                        DropdownMenu(sortMenu, { sortMenu = false }) { SortingLabels.forEach { (option, label) ->
                            DropdownMenuItem({ Text(stringResource(label)) }, { sorting = option; sortMenu = false })
                        } }
                    }
                }
                Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.languages), Modifier.width(79.dp), color = Am.OnDark, fontSize = 14.sp)
                    Box {
                        Text(stringResource(LanguageLabels[language] ?: R.string.all_languages) + "  ˅", Modifier.width(181.dp).background(Am.Well).padding(12.dp)
                            .clickable { languageMenu = true }, color = Am.OnDark, fontSize = 16.sp, maxLines = 1)
                        DropdownMenu(languageMenu, { languageMenu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.all_languages)) }, { language = 0; languageMenu = false })
                            FontCatalog.languages.keys.map { it to stringResource(LanguageLabels.getValue(it)) }.sortedBy { it.second }.forEach { (id, label) ->
                                DropdownMenuItem({ Text(label) }, { language = id; languageMenu = false })
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Am.OnDark)
                Row(Modifier.height(48.dp).fillMaxWidth().clickable { importer.launch(arrayOf("*/*")) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(TextIcons.Import, null, Modifier.padding(6.dp).size(28.dp), tint = Am.OnDark)
                    Spacer(Modifier.width(24.dp)); Text(stringResource(R.string.import_fonts), color = Am.OnDark, fontSize = 16.sp)
                }
            }
        }
    }
    if (loading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)).clickable(interactionSource = null, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Favourite); Text(stringResource(R.string.loading_font), color = Color.White, fontSize = 16.sp) }
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.remove_this_imported_font)) }, text = { Text(entry.name.label) },
        confirmButton = { TextButton({ library.deleteImported(entry.name.xml); deleting = null; revision++ }) { Text(stringResource(R.string.delete_font), color = Am.Accent) } },
        dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark) }
    error?.let { failure -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(failure.title)) }, text = { Text(stringResource(failure.body, failure.detail)) }, confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.ok), color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark) }
}

private class FontError(@StringRes val title: Int, @StringRes val body: Int, val detail: String = "")

private val AllCategories = setOf(1, 2, 3, 4, 5, 100, 110)
private val TitledLanguages = setOf(22, 12, 16, 7, 1, 31, 20, 17)

private val CategoryLabels = mapOf(
    1 to R.string.font_category_sans_serif, 2 to R.string.font_category_serif, 3 to R.string.font_category_display,
    4 to R.string.font_category_handwriting, 5 to R.string.font_category_monospace, 110 to R.string.font_favorite, 100 to R.string.font_imported,
)

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
internal fun FontIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = Am.OnDark)
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
            style = TextStyle(platformStyle = app.absentmotion.platform.fontPreviewStyle()))
    }
}
