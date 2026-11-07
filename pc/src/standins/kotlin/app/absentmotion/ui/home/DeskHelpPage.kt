package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    class Para(val text: AnnotatedString, val note: Boolean = false) : HelpBlock
    class Caption(val text: AnnotatedString) : HelpBlock
    class Picture(val src: String, val alt: String, val width: Int, val height: Int) : HelpBlock
    class Item(val text: AnnotatedString, val marker: String) : HelpBlock
    class Row(val cells: List<AnnotatedString>, val head: Boolean) : HelpBlock
}

private val Tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
private val Href = Regex("href=\"([^\"]*)\"")
private val IdAttr = Regex("id=\"([^\"]*)\"")
private val SrcAttr = Regex("src=\"([^\"]*)\"")
private val AltAttr = Regex("alt=\"([^\"]*)\"")
private val WidthAttr = Regex("width=\"(\\d+)\"")
private val HeightAttr = Regex("height=\"(\\d+)\"")
private val Skipped = setOf("head", "header", "nav", "script", "style", "svg")

private val NamedEntities = mapOf(
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "rarr" to "→", "larr" to "←", "middot" to "·",
    "hellip" to "…", "ndash" to "–", "mdash" to "—", "deg" to "°", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "times" to "×",
)
private val Entity = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

internal fun entities(text: String): String = Entity.replace(text) { m ->
    val v = m.groupValues[1]
    val code = when {
        v.startsWith("#x") || v.startsWith("#X") -> v.drop(2).toIntOrNull(16)
        v.startsWith("#") -> v.drop(1).toIntOrNull()
        else -> null
    }
    if (code != null) code.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value else NamedEntities[v] ?: m.value
}

internal fun helpTarget(page: String, target: String): String? {
    val folder = page.substringBeforeLast('/', "")
    return runCatching { URI(if (folder.isEmpty()) "/" else "/$folder/").resolve(URI.create(target.replace(" ", "%20"))).path.removePrefix("/") }.getOrNull()
}

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
    var inFigure = false
    val divs = ArrayList<Boolean>()
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
            "figcaption" -> out += HelpBlock.Caption(trimmed)
            else -> out += HelpBlock.Para(trimmed, divs.any { it })
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
        if (!closing && name in Skipped || !closing && name == "details" && "toc" in attrs) {
            if (before.isNotBlank() || started) text(before)
            skip = 1
            skipName = name
            continue
        }
        if (before.isNotBlank() || started) text(before)
        when (name) {
            "figure" -> { flush(); inFigure = !closing }
            "div" -> if (closing) { if (divs.isNotEmpty()) divs.removeAt(divs.lastIndex) } else divs += "callout" in attrs
            "img" -> if (inFigure || "fx-thumb" in attrs) {
                flush()
                val src = SrcAttr.find(attrs)?.groupValues?.get(1)
                if (src != null) out += HelpBlock.Picture(
                    src, entities(AltAttr.find(attrs)?.groupValues?.get(1).orEmpty()),
                    WidthAttr.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0, HeightAttr.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                )
            }
            "h1", "h2", "h3", "p", "li", "td", "th", "figcaption" -> if (closing) { flush(); if (name == "li") marker = ""; if (name == "figcaption") kind = "p" } else {
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
            "code", "kbd" -> if (closing) { if (styles.isNotEmpty()) { buffer.pop(); styles.removeAt(styles.lastIndex) } } else { buffer.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Am.HomePanel)); styles += 1 }
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
internal fun DeskHelpPage() = DeskHelpReader("index.html", null, {})

@Composable
private fun HelpPicture(file: File?, block: HelpBlock.Picture) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = file?.let { f -> withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull() } }
    }
    val ratio = if (block.width > 0 && block.height > 0) block.width.toFloat() / block.height else 1.5f
    val wide = if (block.width > 0) minOf(block.width, 560).dp else 560.dp
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.width(wide).aspectRatio(ratio).clip(shape).background(Am.HomePanel).border(1.dp, Am.HomeLine, shape)) {
        bitmap?.let { Image(it, block.alt, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
internal fun DeskHelpReader(start: String, onTitle: ((String) -> Unit)?, onClose: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val tag = remember { currentLanguage(context).tag }
    val history = remember { mutableStateListOf(start) }
    var anchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(start) { if (history.last() != start) { history.clear(); history += start } }
    val page = history.last()
    DeskBack.Handler(onTitle == null && history.size > 1) { history.removeAt(history.lastIndex) }
    val root = remember { android.content.AssetManager.root().resolve("help") }
    fun fileFor(path: String): File? {
        val clean = path.substringBefore('#')
        if (clean.startsWith("/") || clean.contains("..")) return null
        return listOf(root.resolve(tag).resolve(clean), root.resolve(clean)).firstOrNull { it.isFile }
    }
    val open: (String) -> Unit = { target ->
        if (target.startsWith("http")) runCatching { links.openUri(target) }
        else {
            val resolved = helpTarget(page, target)
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
    val helpName = stringResource(R.string.settings_help)
    val title = (blocks?.firstOrNull { it is HelpBlock.Heading && it.level == 1 } as? HelpBlock.Heading)?.text?.text ?: helpName
    LaunchedEffect(title) { onTitle?.invoke(title) }
    val pageFile = fileFor(page)
    val assetRoot = remember { android.content.AssetManager.root().canonicalFile }
    fun imageFile(src: String): File? = pageFile?.parentFile?.resolve(src)?.canonicalFile?.takeIf { it.isFile && it.path.startsWith(assetRoot.path) }
    val windowed = onTitle != null
    Column(Modifier.fillMaxSize().then(if (windowed) Modifier.background(Am.HomeBg) else Modifier)) {
        Row(Modifier.fillMaxWidth().height(if (windowed) 56.dp else 64.dp).padding(start = if (history.size > 1) 12.dp else 24.dp, end = if (windowed) 12.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (history.size > 1) DeskIconButton(app.absentmotion.ui.export.ExportIcons.Back, stringResource(R.string.home_back), { history.removeAt(history.lastIndex) }, tooltip = null)
            Text(if (windowed) title else helpName, Modifier.padding(start = if (history.size > 1) 4.dp else 0.dp).weight(1f), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (history.size > 1) DeskButton(stringResource(R.string.desk_help_contents), { history.clear(); history += "index.html" })
            DeskButton(stringResource(R.string.desk_help_browser), { fileFor(page)?.let { f -> runCatching { java.awt.Desktop.getDesktop().browse(f.toURI()) } } }, Modifier.padding(start = 8.dp))
            if (windowed) DeskIconButton(app.absentmotion.ui.home.HomeIcons.Close, stringResource(R.string.action_close), onClose, Modifier.padding(start = 8.dp), tooltip = "Esc")
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
                        is HelpBlock.Para -> Text(
                            block.text,
                            if (block.note) Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Am.HomePanel).padding(horizontal = 12.dp, vertical = 8.dp) else Modifier,
                            color = Am.HomeInk, fontSize = 14.sp, lineHeight = 21.sp,
                        )
                        is HelpBlock.Caption -> Text(block.text, color = Am.HomeMid, fontSize = 12.sp, lineHeight = 17.sp)
                        is HelpBlock.Picture -> HelpPicture(imageFile(block.src), block)
                        is HelpBlock.Item -> Row(Modifier.padding(start = 8.dp)) {
                            Text(block.marker, Modifier.width(24.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 21.sp)
                            Text(block.text, Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                        is HelpBlock.Row -> Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (block.head) Am.HomePanel else Am.HomeBg).padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            block.cells.forEachIndexed { n, cell -> Text(cell, Modifier.weight(if (n == 0) 1.3f else 1f), color = Am.HomeInk, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (block.head) FontWeight.Bold else FontWeight.Normal) }
                        }
                    }
                }
            }
        }
    }
}
