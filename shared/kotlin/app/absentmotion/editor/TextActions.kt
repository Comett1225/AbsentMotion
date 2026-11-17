package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withContent

fun EditorState.addText() {
    val id = Layer.newId()
    val layer = Layer(XmlNode("text", linkedMapOf("id" to "$id",
        "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, settings.defaultLayerDuration * 1000)}",
        "fillType" to "color", "mediaFillMode" to "fill", "size" to "18.000000",
        "font" to "googlefonts?name=Roboto&weight=400", "wrapWidth" to "512", "align" to "left")))
        .withTransform("location", scene.width / 2f, scene.height / 2f, 0f)
        .withFill("color", 0xFF7F7F7F.toInt()).withContent("")
    edit { it.add(layer) }
    selectedId = id
    addOpen = false
    panel = EditorPanel.EditText
}
