package app.absentmotion.model

import java.util.Locale

enum class DrawTool(val xml: String) {
    Pen("pen"), Brush("brush"), Fill("fill"), Eraser("eraser");

    companion object {
        fun of(text: String?): DrawTool = entries.firstOrNull { it.xml.equals(text, ignoreCase = true) } ?: Pen
    }
}

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

data class Stroke(val points: List<StrokePoint>, val color: Int, val tool: DrawTool, val width: Float) {
    fun toNode(): XmlNode = XmlNode(
        "stroke",
        linkedMapOf(
            "color" to formatColor(color), "width" to String.format(Locale.US, "%.1f", width), "type" to tool.xml,
            "points" to points.joinToString(";") { String.format(Locale.US, "%.2f,%.2f,%.2f", it.x, it.y, it.pressure) },
        ),
    )

    companion object {
        fun parse(node: XmlNode): Stroke = Stroke(
            node["points"].orEmpty().split(';').mapNotNull { group ->
                val v = group.split(',').map { it.trim().finiteFloat() ?: return@mapNotNull null }
                if (v.size >= 3) StrokePoint(v[0], v[1], v[2]) else null
            },
            parseColor(node["color"]) ?: 0xFF0000FF.toInt(),
            DrawTool.of(node["type"]),
            node["width"]?.finiteFloat() ?: 5f,
        )
    }
}

val Layer.strokes: List<Stroke> get() = node.children.filter { it.name == "stroke" }.map(Stroke::parse)

fun Layer.withStroke(stroke: Stroke): Layer = copy(node = node.copy(children = node.children + stroke.toNode()))
