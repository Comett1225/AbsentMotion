package app.absentmotion.editor

import app.absentmotion.model.*

fun EditorState.addVideo(media: MediaDeclaration) {
    if (media.width <= 0 || media.height <= 0 || media.duration <= 0) return
    val document = scene
    val turned = media.orientation == 90 || media.orientation == 270
    val w = if (turned) media.height else media.width
    val h = if (turned) media.width else media.height
    val atWidth = h.toLong() * document.width / w
    val fitted = if (atWidth <= document.height) document.width to atWidth.toInt()
        else (w.toLong() * document.height / h).toInt() to document.height
    val raw = if (turned) fitted.second to fitted.first else fitted
    val id = Layer.newId()
    val duration = (media.duration / 1000).toInt()
    val endFrame = ((document.frameStart(frame) + duration).toLong() * document.fps / 1000).toInt()
    var layer = Layer(XmlNode("shape", linkedMapOf("id" to "$id", "label" to media.title,
        "startTime" to "${document.frameStart(frame)}", "endTime" to "${document.frameEnd(endFrame)}",
        "fillType" to "media", "fillVideo" to media.uri, "outTime" to "$duration", "mediaFillMode" to "fill", "s" to ".rect")))
        .withTransform("location", document.width / 2f, document.height / 2f, 0f)
        .withProp("size", "vec2", raw.first / 2f, raw.second / 2f)
    if (media.orientation != 0) layer = layer.withTransform("rotation", media.orientation.toFloat())
    edit { it.add(layer, listOf(media)) }
    selectedId = id; panel = EditorPanel.Tools; addOpen = false
}

fun EditorState.retimeVideo(start: Boolean) {
    val layer = selected ?: return
    val newStart = if (start) timeMs else layer.startTime
    val newEnd = if (start) layer.endTime else scene.frameEnd(frame)
    val duration = newEnd - newStart
    if (duration < 1 || !scene.showsAnyFrame(newStart, newEnd)) return
    edit { it.update(layer.id) { current -> current.retimedVideo(newStart, newEnd) } }
}

fun EditorState.setVideoSpeed(value: Float, mediaTimeNs: Float, original: Layer) {
    if (selectedId != original.id) return
    val layer = original
    val speed = value.coerceIn(0.001f, 4f)
    val map = layer.node.child("speedMap") ?: XmlNode("speedMap", mapOf("value" to (layer.node["speed"] ?: "1")))
    val snap = layer.atVideoTime(sampleMs, scene.fps).snap
    val changed = map.withValueAt(mediaTimeNs, speed.toString(), snap)
    edit("video-speed") { it.update(layer.id) { layer.withVideoSpeedMap(changed) } }
}

val EditorState.keyLayer: Layer? get() = selected?.let {
    if (keyTarget?.ref == SpeedRef) it.atVideoTime(sampleMs, scene.fps) else it
}

internal fun EditorState.editKeyValue(key: Any? = null, change: (Layer) -> Layer) {
    if (keyTarget?.ref != SpeedRef) { editSelected(key, change); return }
    editSelected(key) { original ->
        val changed = change(original.atVideoTime(sampleMs, scene.fps))
        original.withVideoSpeedMap(changed.node.child("speedMap") ?: XmlNode("speedMap", mapOf("value" to (changed.node["speed"] ?: "1"))))
    }
}

fun EditorState.applySpeedRamp(ramp: SpeedRamp) {
    val id = selected?.id ?: return
    edit { it.update(id) { layer -> layer.withSpeedRamp(ramp) } }
}

fun EditorState.freezeFrame(seconds: Float) {
    val layer = selected ?: return
    val frozen = layer.withFreezeFrame(sampleMs, (seconds * 1000).toInt().coerceIn(100, 30_000), scene.fps) ?: return
    edit { it.update(layer.id) { frozen } }
}

fun EditorState.deleteSpeedKeyframes() {
    val id = selected?.id ?: return
    edit { it.update(id) { layer -> layer.withVideoSpeedMap(XmlNode("speedMap", mapOf("value" to "1"))) } }
}
