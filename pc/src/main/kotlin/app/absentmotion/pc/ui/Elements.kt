package app.absentmotion.pc.ui

import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import java.io.File

fun addText(state: ProjectState) {
    val scene = state.scene
    val frame = scene.frameAt(state.time)
    val id = Layer.newId()
    val layer = Layer(XmlNode("text", linkedMapOf("id" to "$id", "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, 5000)}", "fillType" to "color", "mediaFillMode" to "fill", "size" to "18.000000", "font" to "googlefonts?name=Roboto&weight=400", "wrapWidth" to "512", "align" to "left")))
        .withTransform("location", scene.width / 2f, scene.height / 2f, 0f).withFill("color", 0xFF7F7F7F.toInt()).withContent("Text")
    state.add(layer)
}

fun addShape(state: ProjectState, shape: XmlNode) {
    val scene = state.scene
    val frame = scene.frameAt(state.time)
    val attrs = shape.attrs + mapOf("id" to "${Layer.newId()}", "label" to (shape["name"] ?: "Shape"), "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, 5000)}")
    val layer = Layer(shape.copy(name = "shape", attrs = attrs - "name")).withTransform("location", scene.width / 2f, scene.height / 2f, 0f)
    state.add(layer)
}

fun shapes(assets: File): List<XmlNode> = runCatching { XmlNode.parse(assets.resolve("shapes.xml").readText()).children }.getOrDefault(emptyList())
