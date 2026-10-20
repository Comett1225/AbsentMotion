package app.absentmotion.editor

import app.absentmotion.audio.AUDIO_FX_TAG
import app.absentmotion.audio.GainRef
import app.absentmotion.audio.MAX_GAIN
import app.absentmotion.audio.mutes
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats

fun EditorState.addAudio(media: MediaDeclaration) {
    if (media.duration <= 0) return
    val document = scene
    val duration = (media.duration / 1000).toInt()
    val id = Layer.newId()
    val layer = Layer(XmlNode("audio", linkedMapOf("id" to "$id", "label" to media.title,
        "startTime" to "${document.frameStart(frame)}", "endTime" to "${document.endTimeFor(frame, duration)}",
        "src" to media.uri, "outTime" to "$duration", "mediaFillMode" to "fill")))
    edit { it.add(layer, listOf(media)) }
    selectedId = id; panel = EditorPanel.Tools; addOpen = false
}

fun Layer.extractedAudio(id: Long): Layer? {
    val fill = mediaFill?.takeIf { it.video } ?: return null
    val attrs = linkedMapOf("id" to "$id")
    for (key in listOf("label", "startTime", "endTime")) node[key]?.let { attrs[key] = it }
    attrs["src"] = fill.uri
    for (key in listOf("inTime", "outTime", "loop", "speed")) node[key]?.let { attrs[key] = it }
    attrs["mediaFillMode"] = "fill"
    return Layer(XmlNode("audio", attrs, node.children.filter { it.name == "speedMap" || it.name == "gain" && !it.mutes || it.name == AUDIO_FX_TAG }))
}

fun EditorState.extractAudio() {
    val video = selected ?: return
    val audio = video.extractedAudio(Layer.newId()) ?: return
    edit { scene ->
        scene.add(audio).update(video.id) { it.copy(node = it.node.withChild("gain", XmlNode("gain", mapOf("value" to formatFloats(floatArrayOf(0f)))))) }
    }
    panel = EditorPanel.Tools
}

fun EditorState.setGain(value: Float, key: Any? = null) =
    editSelected(key) { it.withValue(GainRef, formatFloats(floatArrayOf(value.coerceIn(0f, MAX_GAIN)))) }
