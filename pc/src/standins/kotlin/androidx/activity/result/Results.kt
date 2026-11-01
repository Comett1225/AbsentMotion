package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContracts

class PickVisualMediaRequest(val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo)

fun PickVisualMediaRequest(mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType, maxItems: Int) = PickVisualMediaRequest(mediaType)

open class ActivityResultLauncher<I>(private val run: (I) -> Unit) {
    fun launch(input: I) = run(input)
    fun unregister() {}
}
