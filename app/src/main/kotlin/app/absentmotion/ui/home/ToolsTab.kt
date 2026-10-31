package app.absentmotion.ui.home

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.tools.LinkKind
import app.absentmotion.tools.findMediaLink
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor

@Composable
internal fun ToolsTab(modifier: Modifier) {
    var open by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        ToolCard(HomeIcons.Compress, stringResource(R.string.tools_compress), stringResource(R.string.tools_compress_about)) { open = 1 }
        Spacer(Modifier.height(12.dp))
        ToolCard(HomeIcons.Download, stringResource(R.string.tools_downloader), stringResource(R.string.tools_downloader_about)) { open = 2 }
        Spacer(Modifier.height(28.dp))
    }
    when (open) {
        1 -> CompressScreen { open = 0 }
        2 -> DownloaderScreen { open = 0 }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, about: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 104.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).background(Am.Navy, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = Am.OnNavy)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Am.Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(about, color = Am.HomeDim, fontSize = 15.sp, lineHeight = 20.sp)
        }
        Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
    }
}

@Composable
internal fun ToolSheet(title: String, onBack: () -> Unit, bottom: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onBack, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Column(Modifier.fillMaxSize().background(Am.SheetBg).navigationBarsPadding()) {
                TitleBar(title, ExportIcons.Back, stringResource(R.string.home_back), onBack)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), content = content)
                bottom()
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 10.dp), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun ChoiceRow(title: String, about: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = if (about == null) 52.dp else 64.dp).alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp)).background(animatedColor(if (selected) Am.Navy else Am.Card))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) Am.OnNavy else Am.HomeLine), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (selected) 10.dp else 16.dp).clip(CircleShape).background(if (selected) Am.Navy else Am.Card))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) Am.OnNavy else Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (about != null) Text(about, color = if (selected) Am.OnNavy.copy(alpha = 0.8f) else Am.HomeDim, fontSize = 14.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun BigButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.height(52.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(14.dp)).background(if (primary) Am.Accent else Am.HomeLine)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (primary) Am.OnAccent else Am.Navy
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LinkKind.label(): String = stringResource(when (this) {
    LinkKind.Video -> R.string.downloader_kind_video
    LinkKind.Short -> R.string.downloader_kind_short
    LinkKind.Reel -> R.string.downloader_kind_reel
    LinkKind.Live -> R.string.downloader_kind_live
    LinkKind.Story -> R.string.downloader_kind_story
    LinkKind.Clip -> R.string.downloader_kind_clip
    LinkKind.Playlist -> R.string.downloader_kind_playlist
    LinkKind.Post -> R.string.downloader_kind_post
    LinkKind.Photos -> R.string.downloader_kind_photos
})

@Composable
private fun DownloaderScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var pasteMissed by remember { mutableStateOf(false) }
    var save by rememberSaveable { mutableIntStateOf(0) }
    var soon by remember { mutableStateOf(false) }
    val link = remember(text) { findMediaLink(text) }
    val pictures = link?.kind?.pictures == true
    ToolSheet(stringResource(R.string.tools_downloader), onClose, bottom = {
        Column(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp)) {
            BigButton(stringResource(R.string.downloader_download), Modifier.fillMaxWidth(), HomeIcons.Download, enabled = link != null) { soon = true }
            Text(stringResource(R.string.downloader_rights), Modifier.fillMaxWidth().padding(top = 10.dp), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }) {
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(stringResource(R.string.downloader_hint), color = Am.HomeDim, fontSize = 16.sp, maxLines = 1)
                BasicTextField(
                    text, { text = it; pasteMissed = false }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp), cursorBrush = SolidColor(Am.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
            }
            Box(Modifier.size(44.dp).alpha(if (text.isEmpty()) 0f else 1f).clickable(enabled = text.isNotEmpty()) { text = ""; pasteMissed = false }, contentAlignment = Alignment.Center) {
                Icon(HomeIcons.ClearCircle, stringResource(R.string.downloader_clear), Modifier.size(22.dp), tint = Am.HomeMid)
            }
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(Am.Navy).clickable {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val copied = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    val found = findMediaLink(copied)
                    if (found != null) { text = found.url; pasteMissed = false } else pasteMissed = true
                }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.downloader_paste), color = Am.OnNavy, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Text(
            stringResource(if (pasteMissed) R.string.downloader_nothing_to_paste else R.string.downloader_sites),
            Modifier.fillMaxWidth().height(42.dp).padding(top = 6.dp),
            color = if (pasteMissed) Am.Accent else Am.HomeDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (pictures) ExportIcons.Frame else if (link != null) ExportIcons.Video else HomeIcons.Link, null, Modifier.size(28.dp), tint = if (link != null) Am.Navy else Am.HomeDim)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (link != null) {
                    Text(link.site.title, color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(link.kind.label(), color = Am.HomeMid, fontSize = 14.sp, maxLines = 1)
                } else Text(
                    stringResource(if (text.isBlank()) R.string.downloader_waiting else R.string.downloader_unknown),
                    color = Am.HomeDim, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2,
                )
            }
        }
        SectionTitle(stringResource(R.string.downloader_what))
        Box {
            Column(Modifier.alpha(if (pictures) 0f else 1f)) {
                listOf(R.string.downloader_video_audio, R.string.downloader_video_only, R.string.downloader_audio_only).forEachIndexed { i, label ->
                    ChoiceRow(stringResource(label), null, save == i, enabled = !pictures) { save = i }
                }
                SectionTitle(stringResource(R.string.downloader_quality))
                Row(
                    Modifier.fillMaxWidth().height(52.dp).alpha(0.6f).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.downloader_quality_later), Modifier.weight(1f), color = Am.HomeDim, fontSize = 15.sp, maxLines = 2)
                    Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.HomeDim)
                }
            }
            if (pictures) Text(
                stringResource(R.string.downloader_pictures_note),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(16.dp),
                color = Am.HomeInk, fontSize = 15.sp, lineHeight = 20.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    if (soon) AlertDialog(
        onDismissRequest = { soon = false },
        title = { Text(stringResource(R.string.downloader_soon_title), color = Am.Navy) },
        text = { Text(stringResource(R.string.downloader_soon), color = Am.Navy) },
        confirmButton = { TextButton({ soon = false }) { Text(stringResource(R.string.ok), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
