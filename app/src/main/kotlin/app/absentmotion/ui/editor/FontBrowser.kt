package app.absentmotion.ui.editor

import app.absentmotion.ui.theme.Am
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import app.absentmotion.fonts.*
import kotlinx.coroutines.launch

private val BrowserBg get() = Am.PanelInset
private val DrawerBg get() = Am.Panel
private val BrowserLine get() = Am.Line
private val Favourite get() = Am.Gold

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FontBrowser(library: FontLibrary, onSelect: (String) -> Unit, onClose: () -> Unit) {
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
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(search != null) { if (search != null) { searchFocus.requestFocus(); keyboard?.show() } else keyboard?.hide() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val failures = mutableListOf<String>()
            uris.forEach { uri -> runCatching { library.import(uri) }.onFailure { failures.add(uri.lastPathSegment ?: "") } }
            if (failures.isNotEmpty()) error = "The following fonts could not be imported.\n\nThis can happen if a file is not a font, or is a font format that your operating system does not support.\n\n" + failures.joinToString("\n")
            revision++
        }
    }
    val choose = { name: String ->
        if (!loading) scope.launch {
            loading = true
            val file = library.resolve(name)
            loading = false
            if (file != null || library.localFile(name) != null || FontName.parse(name).source != "googlefonts") onSelect(name)
            else error = "That font couldn't be loaded. Check your internet connection and try again."
        }
        Unit
    }
    LaunchedEffect(categories, sorting, language) { library.browserCategories = categories; library.browserSorting = sorting; library.browserLanguage = language }
    val title = remember(categories, language) {
        val all = setOf(1, 2, 3, 4, 5, 100, 110)
        val titled = setOf("Latin", "Greek", "Hebrew", "Cyrillic", "Arabic", "Thai", "Korean", "Japanese")
        val byCategory = FontCatalog.categories + mapOf(100 to "Imported", 110 to "Favorite")
        when {
            categories.isEmpty() || categories.containsAll(all) -> FontCatalog.languages[language]?.takeIf { it in titled }?.let { "$it Fonts" } ?: "All Fonts"
            language == 0 && categories.size == 1 -> "${byCategory[categories.single()]} Fonts"
            else -> "Filtered Fonts"
        }
    }
    BackHandler { when { drawer -> drawer = false; search != null -> search = null; else -> onClose() } }
    val favourites = remember(revision) { library.favourites.toSet() }
    val entries = remember(revision, categories, sorting, language, search) {
        val all = library.importedFonts().map { FontEntry(it, 100, emptySet()) } + FontCatalog.entries
        val filtered = all.filter { entry ->
            val matchesCategory = categories.isEmpty() || entry.category in categories || (110 in categories && entry.name.xml in favourites)
            matchesCategory && (language == 0 || language in entry.languages || entry.category == 100) &&
                (search.isNullOrEmpty() || entry.name.label.contains(search.orEmpty(), ignoreCase = true))
        }
        when (sorting) { "Alphabetical" -> filtered.sortedBy { it.name.label.lowercase() }
            else -> filtered }
    }
    Box(Modifier.fillMaxSize().background(BrowserBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(42.dp).border(0.5.dp, BrowserLine), verticalAlignment = Alignment.CenterVertically) {
                FontIconButton(TextIcons.Menu, "Menu") { drawer = true }
                if (search == null) Text(title, Modifier.weight(1f), color = Am.OnDark, fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                else BasicTextField(search.orEmpty(), { search = it }, Modifier.weight(1f).focusRequester(searchFocus), textStyle = TextStyle(color = Favourite, fontSize = 16.sp),
                    singleLine = true, decorationBox = { inner -> if (search.isNullOrEmpty()) Text("Type here to search", color = Am.OnDark.copy(alpha = .3f), fontSize = 16.sp); inner() })
                FontIconButton(if (search == null) TextIcons.Search else TextIcons.Tick, if (search == null) "Search" else "Done") {
                    search = if (search == null) "" else null
                }
            }
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f)) {
                items(entries, key = { it.name.xml }) { entry ->
                    Box(Modifier.fillMaxWidth().height(100.dp).border(.5.dp, BrowserLine).combinedClickable(onLongClick = { if (entry.category == 100) deleting = entry }) { choose(entry.name.xml) }) {
                        FontPreview(library, entry.name.xml, "Abcdefg", Modifier.align(Alignment.TopCenter).padding(top = 13.dp, start = 10.dp, end = 10.dp).fillMaxWidth().height(46.dp))
                        Text(entry.name.label, Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 25.dp, bottom = 13.dp),
                            color = Am.OnDark, fontSize = 12.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (entry.category == 100) Icon(TextIcons.Import, "Imported", Modifier.padding(8.dp).size(13.dp), tint = Am.OnDark.copy(alpha = .38f))
                        Icon(if (entry.name.xml in favourites) TextIcons.FilledStar else TextIcons.Star, "Favorite", Modifier.align(Alignment.BottomEnd).size(34.dp).clickable {
                            library.favourite(entry.name.xml); revision++
                        }.padding(7.dp), tint = if (entry.name.xml in favourites) Favourite else Am.OnDark.copy(alpha = .38f))
                    }
                }
            }
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).clickable { drawer = false })
            Column(Modifier.width(280.dp).fillMaxHeight().background(DrawerBg).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                listOf(2 to "Serif", 1 to "Sans Serif", 3 to "Display", 4 to "Handwriting", 5 to "Monospace", 110 to "Favorite", 100 to "Imported").forEach { (category, label) ->
                    Row(Modifier.fillMaxWidth().height(40.dp).clickable { categories = if (category in categories) categories - category else categories + category },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(category in categories, null, colors = CheckboxDefaults.colors(checkedColor = Favourite, uncheckedColor = Am.OnDark))
                        Text(label, color = Am.OnDark, fontSize = 16.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Am.OnDark)
                Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sorting", Modifier.width(79.dp), color = Am.OnDark, fontSize = 14.sp)
                    Box {
                        Text(sorting + "  ˅", Modifier.width(181.dp).background(Am.Well).padding(12.dp).clickable { sortMenu = true }, color = Am.OnDark, fontSize = 16.sp)
                        DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Popular", "Alphabetical").forEach { option ->
                            DropdownMenuItem({ Text(option) }, { sorting = option; sortMenu = false })
                        } }
                    }
                }
                Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Languages", Modifier.width(79.dp), color = Am.OnDark, fontSize = 14.sp)
                    Box {
                        Text((FontCatalog.languages[language] ?: "All Languages") + "  ˅", Modifier.width(181.dp).background(Am.Well).padding(12.dp)
                            .clickable { languageMenu = true }, color = Am.OnDark, fontSize = 16.sp, maxLines = 1)
                        DropdownMenu(languageMenu, { languageMenu = false }) {
                            DropdownMenuItem({ Text("All Languages") }, { language = 0; languageMenu = false })
                            FontCatalog.languages.entries.sortedBy { it.value }.forEach { (id, label) ->
                                DropdownMenuItem({ Text(label) }, { language = id; languageMenu = false })
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Am.OnDark)
                Row(Modifier.height(48.dp).fillMaxWidth().clickable { importer.launch(arrayOf("*/*")) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(TextIcons.Import, null, Modifier.padding(6.dp).size(28.dp), tint = Am.OnDark)
                    Spacer(Modifier.width(24.dp)); Text("Import Fonts", color = Am.OnDark, fontSize = 16.sp)
                }
            }
        }
    }
    if (loading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)).clickable(interactionSource = null, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Favourite); Text("Loading Font", color = Color.White, fontSize = 16.sp) }
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove this imported font?") }, text = { Text(entry.name.label) },
        confirmButton = { TextButton({ library.deleteImported(entry.name.xml); deleting = null; revision++ }) { Text("Delete Font", color = Am.Accent) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(if (message.startsWith("The following")) "Import Failed" else "Could not load font") }, text = { Text(message) }, confirmButton = { TextButton({ error = null }) { Text("OK", color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark) }
}

@Composable
internal fun FontIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = Am.OnDark)
    }
}

@Composable
internal fun rememberFontFamily(library: FontLibrary, name: String): FontFamily? =
    produceState<FontFamily?>(null, name) { library.resolve(name); value = app.absentmotion.platform.fontFamily(library, name) }.value

@Composable
internal fun FontPreview(library: FontLibrary, name: String, content: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(content, fontFamily = rememberFontFamily(library, name), color = Am.OnDark, fontSize = 20.sp, maxLines = 1, softWrap = false,
            style = TextStyle(platformStyle = app.absentmotion.platform.fontPreviewStyle()))
    }
}
