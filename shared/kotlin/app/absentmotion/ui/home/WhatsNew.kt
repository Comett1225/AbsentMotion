package app.absentmotion.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.versionName
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

data class WhatsNewGroup(val title: String, val items: List<String>)

data class WhatsNewSection(val title: String, val items: List<String>, val groups: List<WhatsNewGroup> = emptyList()) {
    val count: Int get() = items.size + groups.sumOf { it.items.size }
}

data class WhatsNew(val title: String, val intro: List<String>, val sections: List<WhatsNewSection>) {
    companion object {
        fun parse(text: String): WhatsNew {
            var title = ""
            val intro = mutableListOf<String>()
            val sections = mutableListOf<WhatsNewSection>()
            var name: String? = null
            val items = mutableListOf<String>()
            val groups = mutableListOf<WhatsNewGroup>()
            var group: String? = null
            val grouped = mutableListOf<String>()
            var joinIntro = false
            fun closeGroup() {
                group?.let { if (grouped.isNotEmpty()) groups += WhatsNewGroup(it, grouped.toList()) }
                grouped.clear()
                group = null
            }
            fun close() {
                closeGroup()
                name?.let { if (items.isNotEmpty() || groups.isNotEmpty()) sections += WhatsNewSection(it, items.toList(), groups.toList()) }
                items.clear()
                groups.clear()
            }
            for (raw in text.removePrefix("\uFEFF").lines()) {
                val line = raw.trim()
                val into = if (group == null) items else grouped
                when {
                    line.isEmpty() -> joinIntro = false
                    line.startsWith("## ") -> { close(); name = line.removePrefix("## ").trim() }
                    line.startsWith("### ") && name != null -> { closeGroup(); group = line.removePrefix("### ").trim() }
                    line.startsWith("# ") && name == null && title.isEmpty() -> title = line.removePrefix("# ").trim()
                    name == null -> {
                        if (joinIntro && intro.isNotEmpty()) intro[intro.lastIndex] = intro.last() + " " + line else intro += line
                        joinIntro = true
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> into += line.drop(2).trim()
                    into.isNotEmpty() -> into[into.lastIndex] = into.last() + " " + line
                    else -> into += line
                }
            }
            close()
            return WhatsNew(title, intro, sections)
        }
    }
}

private val Headed = Regex("""^\*\*(.+?)\*\*\s+[-–—]\s+(.+)$""")

private fun capitalised(text: String): String {
    val at = text.indexOfFirst { it.isLetterOrDigit() }
    return if (at < 0) text else text.substring(0, at) + text[at].uppercaseChar() + text.substring(at + 1)
}

private val Bold = Regex("""\*\*(.+?)\*\*""")
private val Link = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

private class Rich(val bold: SpanStyle, val link: TextLinkStyles, val links: (String) -> String?)

private fun rich(text: String, style: Rich): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (match in Bold.findAll(text)) {
        linked(text.substring(at, match.range.first), style)
        withStyle(style.bold) { linked(match.groupValues[1], style) }
        at = match.range.last + 1
    }
    linked(text.substring(at), style)
}

private fun AnnotatedString.Builder.linked(text: String, style: Rich) {
    var at = 0
    for (match in Link.findAll(text)) {
        append(text.substring(at, match.range.first))
        val url = style.links(match.groupValues[2])
        if (url == null) append(match.groupValues[1]) else withLink(LinkAnnotation.Url(url, style.link)) { append(match.groupValues[1]) }
        at = match.range.last + 1
    }
    append(text.substring(at))
}

private class Line(val section: Int, val group: Int, val text: String?, val row: Int, val last: Boolean) {
    val header get() = group < 0 && text == null
}

private fun lines(notes: WhatsNew, open: Set<Int>): List<Line> = buildList {
    add(Line(-1, -1, null, -1, false))
    notes.sections.forEachIndexed { s, section ->
        add(Line(s, -1, null, -1, s !in open))
        if (s in open) {
            val rows = section.items.map { -1 to it } + section.groups.flatMapIndexed { g, group -> listOf(g to null) + group.items.map { g to it } }
            rows.forEachIndexed { n, (g, text) -> add(Line(s, g, text, n, n == rows.lastIndex)) }
        }
    }
}

private data class Pin(val section: Int, val group: Int, val top: Int, val y: Int)

private fun pin(list: LazyListState, lines: List<Line>, height: Int): Pin? {
    val info = list.layoutInfo
    val section = lines.getOrNull(list.firstVisibleItemIndex)?.section ?: return null
    if (section < 0) return null
    val visible = info.visibleItemsInfo.filter { lines.getOrNull(it.index)?.section == section }
    val head = visible.firstOrNull { lines[it.index].header } ?: return null
    val top = head.offset + head.size
    val under = visible.filter { !lines[it.index].header }.firstOrNull { it.offset + it.size > top } ?: return null
    val line = lines[under.index]
    if (line.group < 0 || line.text == null && under.offset >= top) return null
    val end = info.visibleItemsInfo.firstOrNull { it.index > under.index && lines.getOrNull(it.index)?.let { next -> next.section != section || next.group != line.group } == true }
    val y = if (end == null) top else minOf(top, end.offset - height)
    return Pin(section, line.group, top - info.viewportStartOffset, y - info.viewportStartOffset)
}

@Composable
fun WhatsNewCard(notes: WhatsNew, button: String, links: (String) -> String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val style = Rich(SpanStyle(color = Am.HomeInk, fontWeight = FontWeight.Bold), TextLinkStyles(SpanStyle(color = Am.Navy, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)), links)
    var open by remember(notes) { mutableStateOf(setOf(0)) }
    val lines = remember(notes, open) { lines(notes, open) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var labelHeight by remember { mutableIntStateOf(0) }
    val pinned = remember(lines) { derivedStateOf { pin(list, lines, labelHeight) } }
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth().clip(shape).background(Am.Card)) {
        Box(Modifier.weight(1f, fill = false)) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)) {
                lines.forEachIndexed { index, line ->
                    val section = notes.sections.getOrNull(line.section)
                    when {
                        section == null -> item("intro") {
                            Column {
                                Text(notes.title, Modifier.semantics { heading() }, color = Am.Navy, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                                notes.intro.forEach { Text(rich(it, style), Modifier.padding(top = 8.dp), color = Am.HomeMid, fontSize = 15.sp, lineHeight = 21.sp) }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        line.header -> stickyHeader("s${line.section}") { _ ->
                            WhatsNewHeader(section, line.section in open) {
                                val stuck = list.firstVisibleItemIndex > index || list.firstVisibleItemIndex == index && list.firstVisibleItemScrollOffset > 0
                                open = if (line.section in open) open - line.section else open + line.section
                                if (stuck) scope.launch { list.scrollToItem(index) }
                            }
                        }
                        line.text == null -> item("r${line.section}.${line.row}") { WhatsNewLabel(section.groups[line.group], Modifier.animateItem().part(line)) }
                        else -> item("r${line.section}.${line.row}") { WhatsNewItem(line.text, style, Modifier.animateItem().part(line)) }
                    }
                }
            }
            WhatsNewPinned(notes, pinned) { labelHeight = it }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Am.HomeLine))
        Box(Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Am.Navy).clickable(role = Role.Button, onClick = onClose).padding(10.dp), Alignment.Center) {
            Text(button, color = Am.OnNavy, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WhatsNewPinned(notes: WhatsNew, pinned: State<Pin?>, onHeight: (Int) -> Unit) {
    val pin = pinned.value ?: return
    val group = notes.sections.getOrNull(pin.section)?.groups?.getOrNull(pin.group) ?: return
    Box(Modifier.offset { IntOffset(0, pin.top) }.padding(horizontal = 20.dp).fillMaxWidth().clipToBounds()) {
        WhatsNewLabel(group, Modifier.offset { IntOffset(0, pin.y - pin.top) }.onSizeChanged { onHeight(it.height) }.background(Am.HomeBg).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp))
    }
}

private fun Modifier.part(line: Line) = padding(bottom = if (line.last) 10.dp else 0.dp)
    .background(Am.HomeBg, if (line.last) RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp) else RectangleShape)
    .padding(start = 14.dp, end = 14.dp, top = if (line.row == 0) 0.dp else 10.dp, bottom = if (line.last) 14.dp else 0.dp)

@Composable
private fun WhatsNewHeader(section: WhatsNewSection, open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f)
    val shape = if (open) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp) else RoundedCornerShape(14.dp)
    Row(Modifier.padding(bottom = if (open) 0.dp else 10.dp).fillMaxWidth().clip(shape).background(Am.HomeBg).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(section.title, Modifier.weight(1f).semantics { heading() }, color = Am.Navy, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(horizontal = 8.dp).clip(CircleShape).background(Am.Card).padding(horizontal = 9.dp, vertical = 2.dp)) {
            Text(section.count.toString(), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Chevron(Modifier.size(14.dp).rotate(turn))
    }
}

@Composable
private fun WhatsNewLabel(group: WhatsNewGroup, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(group.title, Modifier.weight(1f).semantics { heading() }, color = Am.HomeMid, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
        Text(group.items.size.toString(), Modifier.padding(start = 8.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun WhatsNewItem(text: String, style: Rich, modifier: Modifier) {
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.padding(top = 7.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Am.HomeAccent))
        val headed = Headed.find(text)
        if (headed != null) {
            Column {
                Text(rich(headed.groupValues[1], style), color = Am.Navy, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                Text(rich(capitalised(headed.groupValues[2]), style), Modifier.padding(top = 1.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 19.sp)
            }
        } else {
            Text(rich(text, style), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun Chevron(modifier: Modifier) {
    val colour = Am.HomeMid
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawLine(colour, Offset(w * 0.15f, h * 0.35f), Offset(w * 0.5f, h * 0.7f), stroke, StrokeCap.Round)
        drawLine(colour, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.85f, h * 0.35f), stroke, StrokeCap.Round)
    }
}

object WhatsNewNotes {
    private val Version = Regex("""^\d+\.\d+\.\d+""")

    fun key(versionName: String): String? = Version.find(versionName.trim())?.value

    fun pending(context: Context, settings: AppSettings): WhatsNew? {
        val key = key(versionName(context)) ?: return null
        if (settings.whatsNewSeen == key) return null
        if (settings.onboarding < ONBOARDING_DONE) {
            settings.whatsNewSeen = key
            return null
        }
        return read(context, notesFor(key, settings.whatsNewSeen) { exists(context, it) } ?: return null)
    }

    fun currentName(context: Context): String? = key(versionName(context))?.let { key -> notesFor(key, null) { exists(context, it) } }

    fun current(context: Context): WhatsNew? = currentName(context)?.let { read(context, it) }

    fun unseen(context: Context, settings: AppSettings): Boolean = key(versionName(context)).let { it != null && it != settings.whatsNewSeen }

    private fun exists(context: Context, name: String) = runCatching { context.assets.open("whats-new/$name.md").close() }.isSuccess

    private fun read(context: Context, name: String): WhatsNew? {
        val text = runCatching { context.assets.open("whats-new/$name.md").bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        return WhatsNew.parse(text).takeIf { it.sections.isNotEmpty() }
    }

    fun notesFor(key: String, seen: String?, exists: (String) -> Boolean): String? {
        if (exists(key)) return key
        val release = key.substringBeforeLast('.') + ".0"
        return release.takeIf { it != key && seen?.substringBeforeLast('.') != release.substringBeforeLast('.') && exists(it) }
    }

    fun seen(context: Context, settings: AppSettings) {
        key(versionName(context))?.let { settings.whatsNewSeen = it }
    }
}

@Composable
fun CurrentWhatsNew(settings: AppSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val notes = remember { WhatsNewNotes.current(context) }
    LaunchedEffect(notes) { if (notes == null) onClose() else WhatsNewNotes.seen(context, settings) }
    if (notes != null) WhatsNewDialog(notes, onClose)
}

@Composable
internal fun ChangelogButton(settings: AppSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (remember { WhatsNewNotes.currentName(context) } == null) return
    var open by remember { mutableStateOf(false) }
    Box(modifier.size(50.dp).clickable(role = Role.Button) { open = true }, Alignment.Center) {
        ChangelogIcon(WhatsNewNotes.unseen(context, settings))
    }
    if (open) CurrentWhatsNew(settings) { open = false }
}

@Composable
internal fun ChangelogIcon(dot: Boolean) {
    Box(Modifier.size(24.dp)) {
        Icon(HomeIcons.Notes, stringResource(R.string.update_changelog), Modifier.fillMaxSize(), tint = Am.Navy)
        if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp).size(8.dp).clip(CircleShape).background(Am.HomeAccent))
    }
}

@Composable
fun WhatsNewDialog(notes: WhatsNew, onClose: () -> Unit) {
    val links = rememberReportLinks()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp), Alignment.Center) {
            WhatsNewCard(notes, stringResource(R.string.action_continue), links::named, onClose)
        }
    }
}
