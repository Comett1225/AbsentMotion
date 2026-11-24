package app.absentmotion.ui.home

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.desk.DeskFiles
import app.absentmotion.library.ProjectQrMaxSide
import app.absentmotion.library.projectQrLinks
import app.absentmotion.platform.Context
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.Locale

private val qrPictureExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

@Composable
internal fun ProjectQrPictureButtons(enabled: Boolean, onPicture: (Uri) -> Unit, onDevice: (() -> Unit)? = null) {
    val title = stringResource(R.string.project_link_qr_picture)
    ProjectQrPictureButton(R.string.project_link_qr_picture, enabled) {
        DeskFiles.chooseFiles(true, false, imageOnly = true, title = title, pictureExtensions = qrPictureExtensions)
            .firstOrNull()?.let { onPicture(Uri.fromFile(it)) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun projectQrDropTarget(enabled: Boolean, onPicture: (Uri) -> Unit): Modifier {
    val accept by rememberUpdatedState(enabled)
    val pick by rememberUpdatedState(onPicture)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (!accept) return false
                val transfer = event.awtTransferable
                val files = runCatching { transfer.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> }.getOrNull()
                val picture = files?.filterIsInstance<File>()?.firstOrNull {
                    it.isFile && it.extension.lowercase(Locale.ROOT) in qrPictureExtensions
                } ?: return false
                pick(Uri.fromFile(picture))
                return true
            }
        }
    }
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { accept && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}

internal fun readProjectQrPicture(context: Context, uri: Uri): List<String> {
    val file = uri.toFile() ?: return emptyList()
    val bitmap = BitmapFactory.decodeThumb(file, ProjectQrMaxSide / 2) ?: return emptyList()
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return projectQrLinks(pixels, width, height)
}
