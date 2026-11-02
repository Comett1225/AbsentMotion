package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskBack
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

internal sealed interface HelpBlock {
    class Heading(val level: Int, val text: AnnotatedString, val id: String?) : HelpBlock
    class Para(val text: AnnotatedString) : HelpBlock
    class Item(val text: AnnotatedString, val marker: String) : HelpBlock
    class Row(val cells: List<AnnotatedString>, val head: Boolean) : HelpBlock
}

private val Tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
private val Href = Regex("href=\"([^\"]*)\"")
private val IdAttr = Regex("id=\"([^\"]*)\"")
private val Skipped = setOf("head", "header", "nav", "figure", "script", "style", "svg")

private fun entities(text: String): String = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&rarr;", "→").replace("&larr;", "←").replace("&middot;", "·").replace("&hellip;", "…").replace("&ndash;", "–").replace("&mdash;", "—")

internal fun helpBlocks(html: String, link: SpanStyle, open: (String) -> Unit): List<HelpBlock> {
    val out = ArrayList<HelpBlock>()
    var skip = 0
    var skipName = ""
    var buffer = AnnotatedString.Builder()
    var started = false
    var kind = ""
    var id: String? = null
    var marker = ""
    var row = ArrayList<AnnotatedString>()
    var rowHead = false
    val lists = ArrayList<Pair<Boolean, Int>>()
    val styles = ArrayList<Int>()
    var links = 0
    fun text(t: String) {
        val clean = entities(t).replace(Regex("\\s+"), " ")
        if (!started && clean.isBlank()) return
        buffer.append(if (!started) clean.trimStart() else clean)
        started = true
    }
    fun flush() {
        if (!started) return
        val built = buffer.toAnnotatedString()
        val trimmed = if (built.text.endsWith(" ")) built.subSequence(0, built.length - 1) else built
        if (trimmed.text.isNotBlank()) when (kind) {
            "h1", "h2", "h3" -> out += HelpBlock.Heading(kind.drop(1).toInt(), trimmed, id)
            "li" -> out += HelpBlock.Item(trimmed, marker)
            "td", "th" -> row += trimmed
            else -> out += HelpBlock.Para(trimmed)
        }
        buffer = AnnotatedString.Builder()
        started = false
        id = null
    }
    var at = 0
    val source = html.replace(Regex("<!--.*?-->|<!doctype[^>]*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
    for (match in Tag.findAll(source)) {
        val before = source.substring(at, match.range.first)
        at = match.range.last + 1
        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2].lowercase()
        val attrs = match.groupValues[3]
        if (skip > 0) {
            if (name == skipName) skip += if (closing) -1 else 1
            continue
        }
        if (!closing && name in Skipped || !closing && name == "details" && "toc" in attrs) { skip = 1; skipName = name; continue }
        if (before.isNotBlank() || started) text(before)
        when (name) {
            "h1", "h2", "h3", "p", "li", "td", "th" -> if (closing) { flush(); if (name == "li") marker = "" } else {
                flush()
                kind = name
                id = IdAttr.find(attrs)?.groupValues?.get(1)
                if (name == "li") {
                    val (ordered, n) = lists.lastOrNull() ?: (false to 0)
                    if (lists.isNotEmpty()) lists[lists.lastIndex] = ordered to n + 1
                    marker = if (ordered) "${n + 1}." else "•"
                }
                if (name == "th") rowHead = true
            }
            "tr" -> if (closing) { flush(); if (row.isNotEmpty()) out += HelpBlock.Row(row, rowHead); row = ArrayList(); rowHead = false }
            "ul", "ol" -> if (closing) { flush(); if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex) } else { flush(); lists += (name == "ol") to 0 }
            "strong", "b" -> if (closing) { if (styles.isNotEmpty()) { buffer.pop(); styles.removeAt(styles.lastIndex) } } else { buffer.pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); styles += 1 }
            "em", "i" -> if (closing) { if (styles.isNotEmpty()) { buffer.pop(); styles.removeAt(styles.lastIndex) } } else { buffer.pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); styles += 1 }
            "code" -> if (closing) { if (styles.isNotEmpty()) { buffer.pop(); styles.removeAt(styles.lastIndex) } } else { buffer.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Am.HomePanel)); styles += 1 }
            "span" -> if (!closing && kind == "li" && started) { buffer.append("\n"); buffer.pushStyle(SpanStyle(color = Am.HomeMid, fontSize = 13.sp)); styles += 1 } else if (closing && styles.isNotEmpty() && kind == "li") { buffer.pop(); styles.removeAt(styles.lastIndex) }
            "a" -> if (closing) { if (links > 0) { buffer.pop(); links-- } } else {
                val target = Href.find(attrs)?.groupValues?.get(1)
                if (target != null) { buffer.pushLink(LinkAnnotation.Clickable(target, TextLinkStyles(link)) { open(target) }); links++ }
            }
            "br" -> buffer.append("\n")
        }
    }
    flush()
    return out
}

@Composable
internal fun DeskHelpPage() {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val tag = remember { currentLanguage(context).tag }
    val history = remember { mutableStateListOf("index.html") }
    var anchor by remember { mutableStateOf<String?>(null) }
    val page = history.last()
    DeskBack.Handler(history.size > 1) { history.removeAt(history.lastIndex) }
    val root = remember { android.content.AssetManager.root().resolve("help") }
    fun fileFor(path: String): File? {
        val clean = path.substringBefore('#')
        if (clean.startsWith("/") || clean.contains("..")) return null
        return listOf(root.resolve(tag).resolve(clean), root.resolve(clean)).firstOrNull { it.isFile }
    }
    val open: (String) -> Unit = { target ->
        if (target.startsWith("http")) runCatching { links.openUri(target) }
        else {
            val resolved = runCatching { URI("/" + page.substringBeforeLast('/', "")).resolve(URI.create(target.replace(" ", "%20"))).path.removePrefix("/") }.getOrNull()
            val path = if (target.startsWith("#")) page else resolved ?: target
            anchor = target.substringAfter('#', "").ifEmpty { null }
            if (path.substringBefore('#') != page) history += path.substringBefore('#')
        }
    }
    val linkStyle = remember { SpanStyle(color = Am.HomeAccent, textDecoration = TextDecoration.Underline) }
    val blocks by produceState<List<HelpBlock>?>(null, page) {
        value = null
        value = withContext(Dispatchers.IO) { fileFor(page)?.let { runCatching { helpBlocks(it.readText(), linkStyle, open) }.getOrNull() } ?: emptyList() }
    }
    val list = rememberLazyListState()
    LaunchedEffect(blocks, anchor) {
        val shown = blocks ?: return@LaunchedEffect
        val index = anchor?.let { a -> shown.indexOfFirst { it is HelpBlock.Heading && it.id == a } }?.takeIf { it >= 0 }
        list.scrollToItem(index ?: 0)
        anchor = null
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (history.size > 1) 12.dp else 24.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (history.size > 1) DeskIconButton(app.absentmotion.ui.export.ExportIcons.Back, stringResource(R.string.home_back), { history.removeAt(history.lastIndex) }, tooltip = null)
            Text(stringResource(R.string.settings_help), Modifier.padding(start = if (history.size > 1) 4.dp else 0.dp).weight(1f), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (history.size > 1) DeskButton(stringResource(R.string.desk_help_contents), { history.clear(); history += "index.html" })
            DeskButton(stringResource(R.string.desk_help_browser), { fileFor(page)?.let { f -> runCatching { java.awt.Desktop.getDesktop().browse(f.toURI()) } } }, Modifier.padding(start = 8.dp))
        }
        val shown = blocks
        if (shown == null) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text(stringResource(R.string.about_loading), color = Am.HomeMid, fontSize = 14.sp) }
        else SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(shown) { _, block ->
                    when (block) {
                        is HelpBlock.Heading -> Text(
                            block.text, Modifier.padding(top = if (block.level == 1) 0.dp else 14.dp), color = Am.Navy, fontWeight = FontWeight.Bold,
                            fontSize = when (block.level) { 1 -> 24.sp; 2 -> 18.sp; else -> 15.sp },
                        )
                        is HelpBlock.Para -> Text(block.text, color = Am.HomeInk, fontSize = 14.sp, lineHeight = 21.sp)
                        is HelpBlock.Item -> Row(Modifier.padding(start = 8.dp)) {
                            Text(block.marker, Modifier.width(24.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 21.sp)
                            Text(block.text, Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                        is HelpBlock.Row -> Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (block.head) Am.HomePanel else Am.HomeBg).padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            block.cells.forEach { Text(it, Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (block.head) FontWeight.Bold else FontWeight.Normal) }
                        }
                    }
                }
            }
        }
    }
}
