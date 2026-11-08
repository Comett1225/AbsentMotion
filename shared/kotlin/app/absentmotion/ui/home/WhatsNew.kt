package app.absentmotion.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.Context
import app.absentmotion.platform.versionName
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import app.absentmotion.ui.theme.Am

data class WhatsNewSection(val title: String, val items: List<String>)

data class WhatsNew(val title: String, val intro: List<String>, val sections: List<WhatsNewSection>) {
    companion object {
        fun parse(text: String): WhatsNew {
            var title = ""
            val intro = mutableListOf<String>()
            val sections = mutableListOf<WhatsNewSection>()
            var name: String? = null
            val items = mutableListOf<String>()
            var joinIntro = false
            fun close() {
                name?.let { if (items.isNotEmpty()) sections += WhatsNewSection(it, items.toList()) }
                items.clear()
            }
            for (raw in text.removePrefix("\uFEFF").lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> joinIntro = false
                    line.startsWith("## ") -> { close(); name = line.removePrefix("## ").trim() }
                    line.startsWith("# ") && name == null && title.isEmpty() -> title = line.removePrefix("# ").trim()
                    name == null -> {
                        if (joinIntro && intro.isNotEmpty()) intro[intro.lastIndex] = intro.last() + " " + line else intro += line
                        joinIntro = true
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> items += line.drop(2).trim()
                    items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + line
                    else -> items += line
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

@Composable
fun WhatsNewCard(notes: WhatsNew, button: String, links: (String) -> String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val style = Rich(SpanStyle(color = Am.HomeInk, fontWeight = FontWeight.Bold), TextLinkStyles(SpanStyle(color = Am.Navy, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)), links)
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth().clip(shape).background(Am.Card)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)) {
            Text(notes.title, Modifier.semantics { heading() }, color = Am.Navy, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            notes.intro.forEach { Text(rich(it, style), Modifier.padding(top = 8.dp), color = Am.HomeMid, fontSize = 15.sp, lineHeight = 21.sp) }
            Spacer(Modifier.height(16.dp))
            notes.sections.forEachIndexed { index, section ->
                key(section.title) { WhatsNewBlock(section, index == 0, style) }
                Spacer(Modifier.height(10.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Am.HomeLine))
        Box(Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Am.Navy).clickable(role = Role.Button, onClick = onClose).padding(10.dp), Alignment.Center) {
            Text(button, color = Am.OnNavy, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WhatsNewBlock(section: WhatsNewSection, startOpen: Boolean, style: Rich) {
    var open by remember { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Am.HomeBg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(section.title, Modifier.weight(1f).semantics { heading() }, color = Am.Navy, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.padding(horizontal = 8.dp).clip(CircleShape).background(Am.Card).padding(horizontal = 9.dp, vertical = 2.dp)) {
                Text(section.items.size.toString(), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Chevron(Modifier.size(14.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                section.items.forEach { WhatsNewItem(it, style) }
            }
        }
    }
}

@Composable
private fun WhatsNewItem(text: String, style: Rich) {
    Row {
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
        val shown = notesFor(key, settings.whatsNewSeen) { name -> runCatching { context.assets.open("whats-new/$name.md").close() }.isSuccess } ?: return null
        val text = runCatching { context.assets.open("whats-new/$shown.md").bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        return WhatsNew.parse(text).takeIf { it.sections.isNotEmpty() }
    }

    fun notesFor(key: String, seen: String?, exists: (String) -> Boolean): String? {
        if (exists(key)) return key
        val release = key.substringBeforeLast('.') + ".0"
        return release.takeIf { it != key && it != seen && exists(it) }
    }

    fun seen(context: Context, settings: AppSettings) {
        key(versionName(context))?.let { settings.whatsNewSeen = it }
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
