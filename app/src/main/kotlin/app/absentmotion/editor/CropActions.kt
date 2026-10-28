package app.absentmotion.editor

import app.absentmotion.model.CROP_ID
import app.absentmotion.model.CropBox
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.addEffect
import app.absentmotion.model.cropIndex
import app.absentmotion.model.effects
import app.absentmotion.model.withCropBox
import app.absentmotion.model.withCropKeyframes
import app.absentmotion.model.withEffect

val Layer.canCrop: Boolean get() = mediaFill != null

fun EditorState.openCrop() {
    val layer = selected ?: return
    val index = layer.cropIndex
    if (index < 0) {
        val crop = engine.effects.value.find(CROP_ID)?.takeIf { it.available } ?: return
        editSelected { it.addEffect(crop.instance()) }
    } else if (layer.effects[index]["hidden"] == "true") {
        editSelected { it.withEffect(index) { e -> e.with("hidden", null) } }
    }
    playing = false
    keypad = null
    easingOpen = false
    cropAspect = null
    panel = EditorPanel.Crop
}

fun EditorState.setCrop(box: CropBox, key: Any? = null) {
    val index = selected?.cropIndex?.takeIf { it >= 0 } ?: return
    editSelected(key) { it.withCropBox(index, box) }
}

fun EditorState.toggleCropKeyframes() {
    val index = selected?.cropIndex?.takeIf { it >= 0 } ?: return
    editSelected { it.withCropKeyframes(index) }
}

fun uncropped(id: Long, index: Int): (Scene) -> Scene = { scene ->
    scene.update(id) { layer -> layer.withEffect(index) { it.with("hidden", "true") } }
}
