package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.links.AlightClient
import app.absentmotion.links.AlightLinks
import app.absentmotion.links.AlightPreview
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.platform.alightInstalled
import app.absentmotion.platform.openAlight
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeImportMenu(onDismiss: () -> Unit, onFile: () -> Unit, onLink: () -> Unit, onNew: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.desk_import)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (onNew != null) TextButton({ onDismiss(); onNew() }) { Text(stringResource(R.string.home_new_project)) }
                TextButton({ onDismiss(); onFile() }) { Text(stringResource(R.string.alight_import_file)) }
                TextButton({ onDismiss(); onLink() }) { Text(stringResource(R.string.alight_link)) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Am.Card, titleContentColor = Am.Navy, textContentColor = Am.HomeInk,
    )
}

@Suppress("DEPRECATION")
@Composable
internal fun AlightLinkEntry(initial: String = "", onDismiss: () -> Unit, onLink: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var value by remember { mutableStateOf(initial) }
    var pasteMissed by remember { mutableStateOf(false) }
    val link = AlightLinks.parse(value.trim())?.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alight_link)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value, { value = it.take(4096); pasteMissed = false }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.alight_link_field)) }, singleLine = false, minLines = 4, maxLines = 4,
                    isError = value.isNotBlank() && link == null)
                TextButton({
                    val pasted = clipboard.getText()?.text.orEmpty()
                    value = AlightLinks.extract(pasted) ?: pasted.take(4096)
                    pasteMissed = pasted.isBlank()
                }) { Text(stringResource(R.string.downloader_paste)) }
                Box(Modifier.fillMaxWidth().height(72.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(if (pasteMissed) R.string.alight_paste_empty else if (value.isNotBlank() && link == null) R.string.alight_invalid else R.string.alight_entry_hint), color = Am.HomeDim)
                }
            }
        },
        confirmButton = { TextButton({ link?.let(onLink) }, enabled = link != null) { Text(stringResource(R.string.open)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Am.Card, titleContentColor = Am.Navy, textContentColor = Am.HomeInk,
    )
}

@Suppress("DEPRECATION")
@Composable
internal fun AlightLinkHelper(link: String, onDismiss: () -> Unit, loadPreview: Boolean = true) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val client = remember(link) { AlightClient() }
    var preview by remember(link) { mutableStateOf(AlightPreview()) }
    var picture by remember(link) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(link) { mutableStateOf(loadPreview) }
    var failed by remember(link) { mutableStateOf(!loadPreview) }
    var actionFailed by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(alightInstalled(context)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { installed = alightInstalled(context) }
    DisposableEffect(client) { onDispose { client.abort() } }
    LaunchedEffect(link) {
        if (loadPreview) {
            val found = withContext(Dispatchers.IO) { runCatching { client.preview(link) }.getOrNull() }
            preview = found ?: AlightPreview()
            failed = found == null
            if (found != null) picture = withContext(Dispatchers.IO) {
                runCatching {
                    client.image(found)?.let { bytes ->
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val sample = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 640).coerceAtLeast(1))
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    }
                }.getOrNull()
            }
            loading = false
        }
    }
    ToolSheet(stringResource(R.string.alight_link), onDismiss, closeButton = true) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(180.dp).background(Am.HomePanel), Alignment.Center) {
                val thumb = picture
                if (thumb == null) Icon(HomeIcons.ProjectsTabOff, null, Modifier.size(48.dp), tint = Am.HomeDim)
                else Image(thumb, null, Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
            }
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Text(preview.title ?: stringResource(R.string.alight_link), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SelectionContainer {
                Box(Modifier.fillMaxWidth().height(88.dp).verticalScroll(rememberScrollState())) {
                    Text(preview.description ?: stringResource(R.string.alight_preview_only), color = Am.HomeInk, fontSize = 15.sp)
                }
            }
            SelectionContainer { Text(link, color = Am.HomeDim, fontSize = 13.sp) }
            Box(Modifier.fillMaxWidth().height(72.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(if (actionFailed) R.string.alight_open_failed else if (loading) R.string.alight_loading else if (failed) R.string.alight_preview_failed else R.string.alight_preview_only), color = Am.HomeDim, fontSize = 13.sp)
            }
            if (PlatformFeatures.desktop) TextButton({ clipboard.setText(AnnotatedString(link)) }) { Text(stringResource(R.string.alight_copy_link), color = Am.Navy) }
            else TextButton({ actionFailed = !openAlight(context, link, installed) }) {
                Text(stringResource(if (installed) R.string.alight_open_app else R.string.alight_get_app), color = Am.Navy)
            }
            Text(stringResource(if (PlatformFeatures.desktop) R.string.alight_phone_steps else R.string.alight_steps), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            for (step in listOf(R.string.alight_step_import, R.string.alight_step_open, R.string.alight_step_xml, R.string.alight_step_report)) {
                Text(stringResource(step), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
    }
}
