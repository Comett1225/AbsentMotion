package app.absentmotion.ui.editor.effects

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.absentmotion.effects.MediaLook
import app.absentmotion.effects.mediaLook
import app.absentmotion.effects.screenFromName
import app.absentmotion.library.openableUri
import app.absentmotion.model.MediaFill
import app.absentmotion.ui.editor.videoFrameSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val looks = ConcurrentHashMap<String, MediaLook>()

internal fun knownLook(media: MediaFill): MediaLook? = looks[lookKey(media)]

internal suspend fun mediaLookOf(context: Context, media: MediaFill): MediaLook {
    looks[lookKey(media)]?.let { return it }
    val image = try {
        if (media.video) videoFrameSample(context, media.uri, media.inTime * 1000L + 300_000L)
        else withContext(Dispatchers.IO) { smallPicture(context, media.uri) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    val seen = image?.let { val pixels = IntArray(it.width * it.height); it.readPixels(pixels); mediaLook(pixels, it.width, it.height) } ?: MediaLook()
    val look = if (seen.screen == null) seen.copy(screen = screenFromName(media.uri)) else seen
    if (looks.size > 256) looks.clear()
    looks[lookKey(media)] = look
    return look
}

private fun lookKey(media: MediaFill) = "${media.uri}#${media.inTime}"

private fun smallPicture(context: Context, uri: String): ImageBitmap? {
    val bytes = context.contentResolver.openInputStream(Uri.parse(openableUri(uri)))?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 160 || bounds.outHeight / sample > 160) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
