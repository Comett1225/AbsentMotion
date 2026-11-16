package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withContent

fun EditorState.addText() {
    val layer = scene.textLayer(frame, settings.defaultLayerDuration * 1000, "", null)
    edit { it.add(layer) }
    selectedId = layer.id
    addOpen = false
    panel = EditorPanel.EditText
}

fun Scene.textLayer(frame: Int, lengthMs: Int, content: String, colour: Int?): Layer =
    Layer(XmlNode("text", linkedMapOf("id" to "${Layer.newId()}",
        "startTime" to "${frameStart(frame)}", "endTime" to "${endTimeFor(frame, lengthMs)}",
        "fillType" to "color", "mediaFillMode" to "fill", "size" to "18.000000",
        "font" to "googlefonts?name=Roboto&weight=400", "wrapWidth" to "512", "align" to "left")))
        .withTransform("location", width / 2f, height / 2f, 0f)
        .withFill("color", colour ?: 0xFF7F7F7F.toInt()).withContent(content)
