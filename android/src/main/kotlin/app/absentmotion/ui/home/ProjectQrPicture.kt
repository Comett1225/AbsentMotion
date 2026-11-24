package app.absentmotion.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.library.ProjectQrMaxSide
import app.absentmotion.library.projectQrLinks
import app.absentmotion.platform.Context
import app.absentmotion.platform.rememberLauncherForActivityResult

@Composable
internal fun ProjectQrPictureButtons(enabled: Boolean, onPicture: (Uri) -> Unit, onDevice: (() -> Unit)? = null) {
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(onPicture) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPicture) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectQrPictureButton(R.string.project_link_qr_gallery, enabled) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        ProjectQrPictureButton(R.string.project_link_qr_files, enabled) { files.launch(arrayOf("image/*")) }
        if (onDevice != null) ProjectQrPictureButton(R.string.send_from_device, enabled, onDevice)
    }
}

@Composable
internal fun projectQrDropTarget(enabled: Boolean, onPicture: (Uri) -> Unit): Modifier = Modifier

internal fun readProjectQrPicture(context: Context, uri: Uri): List<String> {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return emptyList()
    var sample = 1
    while (longest / sample > ProjectQrMaxSide * 3 / 2) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return emptyList()
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        projectQrLinks(pixels, width, height)
    } finally {
        bitmap.recycle()
    }
}
