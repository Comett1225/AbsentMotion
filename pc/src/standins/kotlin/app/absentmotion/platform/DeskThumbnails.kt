package app.absentmotion.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

private class Animation(val frames: List<ImageBitmap>, val durations: IntArray)

private val animations = object : LinkedHashMap<String, Animation>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Animation>?) = size > 48
}

private fun decode(bytes: ByteArray): Animation? = runCatching {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val count = codec.frameCount.coerceAtLeast(1)
    val info = codec.framesInfo
    val frames = (0 until count).map { i ->
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocPixels(codec.imageInfo)
        codec.readPixels(bitmap, i)
        bitmap.setImmutable()
        bitmap.asComposeImageBitmap()
    }
    Animation(frames, IntArray(count) { info.getOrNull(it)?.duration?.coerceAtLeast(20) ?: 100 })
}.getOrNull()

@Composable
fun EffectPicture(catalog: app.absentmotion.effects.EffectCatalog, path: String?, scale: ContentScale) {
    val key = path ?: return
    val animation by produceState(synchronized(animations) { animations[key] }, catalog, key) {
        if (value == null) value = withContext(Dispatchers.IO) { catalog.bytes(key)?.let(::decode) }?.also { synchronized(animations) { animations[key] = it } }
    }
    val shown = animation ?: return
    var frame by remember(shown) { mutableIntStateOf(0) }
    if (shown.frames.size > 1) LaunchedEffect(shown) {
        while (true) {
            delay(shown.durations[frame].toLong())
            frame = (frame + 1) % shown.frames.size
        }
    }
    Image(shown.frames[frame.coerceIn(0, shown.frames.size - 1)], null, Modifier.fillMaxSize(), contentScale = scale)
}
