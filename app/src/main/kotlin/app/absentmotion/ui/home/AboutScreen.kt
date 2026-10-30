package app.absentmotion.ui.home

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.Dialog
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.appVersion
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.Tier

internal const val HOME_PAGE ="https://github.com/Comett1225/AbsentMotion"
internal const val REPORT_FORM = "https://forms.gle/6bY8KBPMKc2iUypV6"

internal const val LICENCES_ASSET = "licenses.txt"

@Composable
fun AboutScreen(onClose: () -> Unit) {
    var licences by remember { mutableStateOf(false) }
    Dialog({ if (licences) licences = false else onClose() }, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Box(Modifier.fillMaxSize().background(Am.SheetBg)) {
                if (licences) LicencesPage { licences = false }
                else AboutPage(onClose, onLicences = { licences = true })
            }
        }
    }
}

@Composable
private fun AboutPage(onClose: () -> Unit, onLicences: () -> Unit) {
    val version = appVersion()
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBar(stringResource(R.string.settings_about), HomeIcons.Close, stringResource(R.string.action_close), onClose)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("AbsentMotion", color = Am.Navy, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (version.isNotEmpty()) Text(stringResource(if (Tier.compatible) R.string.about_version_compatible else R.string.about_version, version),
                Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            Body(stringResource(R.string.about_licence_line))
            Spacer(Modifier.height(12.dp))
            Body(stringResource(R.string.about_independent_note))
            Spacer(Modifier.height(12.dp))
            Body(stringResource(R.string.about_compatibility_note))
            compatibleNote()?.let {
                Spacer(Modifier.height(12.dp))
                Body(it)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RowShape).background(Am.Card).clickable(onClick = onLicences).padding(start = 15.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.open_source_licences), Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp)
                Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.project_home_page), color = Am.HomeMid, fontSize = 12.sp)
            SelectionContainer { Text(HOME_PAGE, Modifier.padding(top = 2.dp), color = Am.HomeInk, fontSize = 14.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val RowShape = RoundedCornerShape(7.dp)

@Composable
private fun Body(text: String) = Text(text, color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
internal fun TitleBar(
    title: String, icon: ImageVector, description: String, onClick: () -> Unit,
    action: ImageVector? = null, actionDescription: String = "", onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().background(Am.Card).statusBarsPadding().height(49.dp)) {
        Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp), tint = Am.Navy)
        }
        Text(title, Modifier.align(Alignment.Center), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (action != null) Box(Modifier.align(Alignment.TopEnd).size(44.dp).clickable(onClick = onAction), contentAlignment = Alignment.Center) {
            Icon(action, actionDescription, Modifier.size(24.dp), tint = Am.Navy)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(Am.HomeLine))
    }
}

@Composable
private fun LicencesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val parts by produceState<List<LicencePart>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { licenceParts(context.assets.open(LICENCES_ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBar(stringResource(R.string.open_source_licences), ExportIcons.Back, stringResource(R.string.home_back), onBack)
        val shown = parts
        if (shown == null || shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (shown == null) R.string.about_loading else R.string.about_licence_unreadable), color = Am.HomeMid, fontSize = 14.sp)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            items(shown) { part ->
                val gap = if (part.last) 12.dp else 0.dp
                when (part.heading) {
                    0 -> Text(part.text, Modifier.padding(bottom = gap), color = Am.HomeInk, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                    else -> Text(
                        part.text, Modifier.padding(top = if (part.heading == 2) 16.dp else 6.dp, bottom = gap), color = Am.Navy,
                        fontSize = if (part.heading == 2) 17.sp else 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

internal class LicencePart(val text: String, val heading: Int, val last: Boolean)

private const val CHUNK = 24

internal fun licenceParts(text: String): List<LicencePart> {
    val parts = ArrayList<LicencePart>()
    val block = ArrayList<String>()
    fun end() {
        if (block.isEmpty()) return
        val underline = block.getOrNull(1)?.trim().orEmpty()
        if (block.size == 2 && underline.length >= 3 && underline.all { it == '-' || it == '=' }) {
            val title = block[0].trim()
            parts += LicencePart(title, if (underline[0] == '=' && title == title.uppercase()) 2 else 1, true)
        } else {
            val margin = block.minOf { indent(it) }
            val lines = reflow(if (margin > 8) block.map { it.drop(margin - 4) } else block)
            lines.chunked(CHUNK).forEachIndexed { i, chunk -> parts += LicencePart(chunk.joinToString("\n"), 0, (i + 1) * CHUNK >= lines.size) }
        }
        block.clear()
    }
    for (line in text.lines()) if (line.isBlank()) end() else block += line.trimEnd().replace("\t", "    ")
    end()
    return parts
}

private val Marker = Regex("""^\s*([-*•]|o|\d+[.)]|\(\w{1,3}\)|\w[.)])\s+\S""")

private fun indent(line: String) = line.length - line.trimStart().length

internal fun reflow(lines: List<String>): List<String> {
    val width = lines.maxOf { it.length }
    val reaches = { line: String -> line.length >= width - 12 }
    if (lines.size < 2 || width !in 50..100 || lines.dropLast(1).count(reaches) * 5 < (lines.size - 1) * 3) return lines
    val out = ArrayList<String>()
    var before = ""
    for (line in lines) {
        val joins = out.isNotEmpty() && reaches(before) && !Marker.containsMatchIn(line) &&
            (indent(line) == indent(before) || (indent(line) > indent(before) && Marker.containsMatchIn(before)))
        if (joins) out[out.lastIndex] = out.last() + " " + line.trim() else out += line
        before = line
    }
    return out
}
