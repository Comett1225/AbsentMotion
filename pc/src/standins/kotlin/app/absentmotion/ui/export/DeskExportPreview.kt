package app.absentmotion.ui.export

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.ExportKind
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.home.AtPosition
import app.absentmotion.ui.home.DeskMenuItem
import app.absentmotion.ui.home.DeskPopupMenu
import app.absentmotion.ui.home.onSecondaryPress
import androidx.compose.ui.unit.IntOffset
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File

private const val PreviewSide = 720

@Composable
internal fun DeskExportPreview(kind: ExportKind, uris: List<Uri>, large: Boolean = false, onTap: (() -> Unit)? = null) {
    var full by remember { mutableStateOf(false) }
    val viewable = kind != ExportKind.Package && kind != ExportKind.Xml && kind != ExportKind.Audio
    val open = if (!viewable || large) null else onTap ?: { full = true }
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(
        Modifier.fillMaxSize().then(if (open != null) Modifier.handCursor().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = open) else Modifier)
            .onSecondaryPress { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) },
    ) {
        when (kind) {
            ExportKind.Video, ExportKind.Gif -> MoviePreview(uris.first(), !full)
            ExportKind.Frame, ExportKind.FrameAs -> PicturePreview(uris.first())
            ExportKind.Sequence -> SequencePreview(uris)
            ExportKind.Package, ExportKind.Xml, ExportKind.Audio -> FilePreview(uris.first())
        }
        menuAt?.let { at ->
            DeskPopupMenu(AtPosition(at), { menuAt = null }, 160.dp) {
                DeskMenuItem(stringResource(R.string.desk_export_copy), { menuAt = null; copyFiles(uris.mapNotNull { it.toFile() }) }, ChromeIcons.Copy)
            }
        }
    }
    if (full) DeskMediaViewer(kind, uris) { full = false }
}

internal fun copyFiles(files: List<File>): Boolean {
    val kept = files.filter { it.isFile }.ifEmpty { return false }
    val transfer = object : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any =
            if (isDataFlavorSupported(flavor)) kept else throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }
    return runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transfer, null) }.isSuccess
}

@Composable
private fun PicturePreview(uri: Uri) {
    val picture by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(uri.toFile()!!.readBytes()).toComposeImageBitmap() }.getOrNull() }
    }
    Box(Modifier.fillMaxSize()) { picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
}

@Composable
private fun SequencePreview(uris: List<Uri>) {
    var index by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { PicturePreview(uris[index]) }
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).clickable(enabled = index > 0) { index-- }, contentAlignment = Alignment.Center) {
                Icon(ExportIcons.Prev, stringResource(R.string.export_previous), tint = Am.OnScrim.copy(alpha = if (index > 0) 1f else 0.4f))
            }
            Text("${index + 1} / ${uris.size}", Modifier.weight(1f), color = Am.OnScrim, fontSize = 13.sp, textAlign = TextAlign.Center)
            Box(Modifier.size(62.dp).clickable(enabled = index < uris.size - 1) { index++ }, contentAlignment = Alignment.Center) {
                Icon(ExportIcons.Next, stringResource(R.string.export_next), tint = Am.OnScrim.copy(alpha = if (index < uris.size - 1) 1f else 0.4f))
            }
        }
    }
}

@Composable
private fun MoviePreview(uri: Uri, active: Boolean) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, active) {
        val file = uri.toFile()?.takeIf { active } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { play(file) { frame = it; isActive } }
    }
    Box(Modifier.fillMaxSize()) {
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

private fun play(file: File, show: (ImageBitmap) -> Boolean) {
    val facts = DeskMedia.probe(file)
    val w0 = facts["width"]?.toIntOrNull() ?: return
    val h0 = facts["height"]?.toIntOrNull() ?: return
    val scale = minOf(1.0, PreviewSide.toDouble() / maxOf(w0, h0))
    val w = maxOf(2, (w0 * scale).toInt() and 1.inv())
    val h = maxOf(2, (h0 * scale).toInt() and 1.inv())
    val stream = app.absentmotion.media.MediaEngine.current.play(file, 0, w, h, true)
    try {
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val input = stream.input
        while (true) {
            val bytes = input.readNBytes(w * h * 4)
            if (bytes.size < w * h * 4) return
            if (!show(org.jetbrains.skia.Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap())) return
        }
    } finally {
        stream.close()
    }
}

@Composable
private fun FilePreview(uri: Uri) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(ExportIcons.File, null, Modifier.size(72.dp), tint = Am.OnScrim)
        Spacer(Modifier.height(14.dp))
        Text(uri.toFile()?.name ?: uri.toString(), color = Am.OnScrim, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
