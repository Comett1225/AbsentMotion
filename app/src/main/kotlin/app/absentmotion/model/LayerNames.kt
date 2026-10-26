package app.absentmotion.model

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.i18n.AppText

@StringRes
fun Layer.nameResource(): Int = when (kind) {
    "nullobj" -> R.string.model_layer_null
    "camera" -> R.string.model_layer_camera
    "embedScene" -> if (link != null) R.string.model_layer_element else R.string.model_layer_group
    "drawing" -> R.string.model_layer_drawing
    "track" -> R.string.model_layer_track
    "model" -> R.string.model_layer_model
    "text" -> R.string.add_quick_text
    "audio" -> R.string.add_tab_audio
    "svg" -> R.string.model_layer_svg
    "shape" -> mediaFill?.let { if (it.video) R.string.model_layer_video else R.string.model_layer_image } ?: R.string.shape_name_shape
    else -> R.string.model_layer_layer
}

fun Layer.shownName(resolve: (Int) -> String = { AppText.get(it) }): String = label.ifBlank { resolve(nameResource()) }
