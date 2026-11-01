package app.absentmotion.pc.ui

import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.withTransform

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

fun addSolid(state: ProjectState) {
    val scene = state.scene
    val frame = scene.frameAt(state.time)
    val layer = Layer(XmlNode("shape", mapOf("id" to "${Layer.newId()}", "label" to "Solid color", "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, 5000)}", "s" to ".rect", "fillType" to "color")))
        .withTransform("location", scene.width / 2f, scene.height / 2f, 0f).withProp("size", "vec2", scene.width / 2f, scene.height / 2f).withFill("color", 0xff7f7f7f.toInt())
    state.add(layer)
}

@androidx.compose.runtime.Composable
fun Elements(state: ProjectState, assets: File) {
    val items = androidx.compose.runtime.remember(assets) { shapes(assets) }
    androidx.compose.foundation.lazy.LazyColumn {
        item { ElementRow("Text", "text") { addText(state) } }
        item { ElementRow("Solid color", "solid") { addSolid(state) } }
        item { SectionTitle("Shapes") }
        items(items.size) { index ->
            val shape = items[index]
            ElementRow(shape["name"] ?: "Shape", shape["s"] ?: "", shape) { addShape(state, shape) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ElementRow(name: String, kind: String, shape: XmlNode? = null, add: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().androidxDrag(add).clickable { add() }.padding(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        val paths = androidx.compose.runtime.remember(shape) {
            val node = shape?.children?.firstOrNull { it.name == "path" }
            val entries = if (node?.get("d") != null) listOf(node) else node?.children.orEmpty()
            entries.mapNotNull { entry -> entry["d"]?.let { data -> runCatching { androidx.compose.ui.graphics.vector.PathParser().parsePathString(data).toPath() to (entry["exclude"] == "true") }.getOrNull() } }
        }
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (kind == "text") androidx.compose.material.Text("T", fontSize = 26.sp, color = androidx.compose.ui.graphics.Color(0xffc4c6cc))
            else androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
                val color = androidx.compose.ui.graphics.Color(0xffc4c6cc)
                if (paths.isNotEmpty()) {
                    val combined = androidx.compose.ui.graphics.Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        paths.forEach { addPath(it.first) }
                    }
                    withTransform({ translate(size.width / 2f, size.height / 2f); scale(size.minDimension / 260f, size.minDimension / 260f, androidx.compose.ui.geometry.Offset.Zero) }) { drawPath(combined, color) }
                } else when {
                    kind.contains("circle") -> drawCircle(color, radius = size.minDimension * .36f)
                    kind.contains("arc") -> drawArc(color, 180f, 120f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    kind.contains("pie") -> drawArc(color, -90f, 280f, true)
                    kind.contains("line") -> drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * .7f), androidx.compose.ui.geometry.Offset(size.width * .85f, size.height * .3f), if (kind.contains("wide")) 6.dp.toPx() else 2.dp.toPx())
                    kind.contains("arrow") -> {
                        val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * .1f, size.height * .35f); lineTo(size.width * .55f, size.height * .35f); lineTo(size.width * .55f, size.height * .15f); lineTo(size.width * .9f, size.height * .5f); lineTo(size.width * .55f, size.height * .85f); lineTo(size.width * .55f, size.height * .65f); lineTo(size.width * .1f, size.height * .65f); close() }
                        drawPath(path, color)
                    }
                    kind.contains("moon") -> {
                        val path = androidx.compose.ui.graphics.Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd; addOval(androidx.compose.ui.geometry.Rect(size.width * .1f, size.height * .1f, size.width * .9f, size.height * .9f)); addOval(androidx.compose.ui.geometry.Rect(size.width * .4f, 0f, size.width, size.height * .7f)) }
                        drawPath(path, color)
                    }
                    kind.contains("multifoil") -> repeat(5) { i ->
                        val angle = 2 * Math.PI * i / 5
                        drawCircle(color, size.minDimension * .2f, androidx.compose.ui.geometry.Offset(size.width * (.5f + .2f * kotlin.math.cos(angle).toFloat()), size.height * (.5f + .2f * kotlin.math.sin(angle).toFloat())))
                    }
                    kind.contains("plus") -> { drawRect(color, androidx.compose.ui.geometry.Offset(size.width * .4f, size.height * .1f), androidx.compose.ui.geometry.Size(size.width * .2f, size.height * .8f)); drawRect(color, androidx.compose.ui.geometry.Offset(size.width * .1f, size.height * .4f), androidx.compose.ui.geometry.Size(size.width * .8f, size.height * .2f)) }
                    kind.contains("triangle") || kind.contains("poly") || kind.contains("star") || kind.contains("penta") -> {
                        val count = when { kind.contains("triangle") -> 3; kind.contains("star") -> 10; else -> 5 }
                        val path = androidx.compose.ui.graphics.Path()
                        repeat(count) { i ->
                            val angle = -Math.PI / 2 + 2 * Math.PI * i / count
                            val radius = size.minDimension * if (kind.contains("star") && i % 2 == 1) .18f else .4f
                            val x = size.width / 2 + kotlin.math.cos(angle).toFloat() * radius
                            val y = size.height / 2 + kotlin.math.sin(angle).toFloat() * radius
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close(); drawPath(path, color)
                    }
                    else -> drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .18f), size = androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(if (kind.contains("round")) 6.dp.toPx() else 0f))
                }
            }
        }
        androidx.compose.material.Text(name, androidx.compose.ui.Modifier.padding(start = 8.dp), fontSize = 12.sp)
    }
}

private fun androidx.compose.ui.Modifier.androidxDrag(add: () -> Unit): androidx.compose.ui.Modifier =
    this.pointerInput(add) {
        var outside = false
        detectDragGestures(onDragStart = { outside = false }, onDragEnd = { if (outside) add() }) { change, _ -> outside = change.position.x > size.width; change.consume() }
    }
