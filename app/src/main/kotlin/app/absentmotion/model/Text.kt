package app.absentmotion.model

import kotlin.math.cos
import kotlin.math.sin

val Layer.content: String get() = node.child("content")?.text ?: ""
val Layer.font: String get() = node["font"] ?: ""
val Layer.textSize: Float get() = node["size"]?.toFloatOrNull() ?: 18f
val Layer.wrapWidth: Int get() = node["wrapWidth"]?.toIntOrNull() ?: 512
val Layer.textAlign: String get() = node["align"] ?: "left"
fun Layer.withContent(value: String) = copy(node = node.withChild("content", XmlNode("content", text = value)))
fun Layer.withFont(value: String) = copy(node = node.with("font", value))
fun Layer.withTextSize(value: Float) = copy(node = node.with("size", formatFloats(floatArrayOf(value))))
fun Layer.withWrapWidth(value: Int) = copy(node = node.with("wrapWidth", "${value.coerceAtLeast(1)}"))
fun Layer.withTextAlign(value: String) = copy(node = node.with("align", value))
fun Layer.textSpacing(name: String, fallback: Float): Float = node.children
    .firstOrNull { it.name == "effect" && it["id"] == app.absentmotion.compat.ForeignEffectIds.TEXT_SPACING && it["hidden"] != "true" }
    ?.children?.firstOrNull { it.name == "property" && it["name"] == name }
    ?.valueAt(t)?.toFloatOrNull() ?: fallback

fun Layer.withResizedTextWrap(width: Int): Layer {
    val offset = when (textAlign) { "center" -> 0f; "right" -> -(width - wrapWidth) / 2f; else -> (width - wrapWidth) / 2f }
    if (offset == 0f) return withWrapWidth(width)
    val turn = Math.toRadians(transform("rotation", 0f)[0].toDouble())
    val along = offset * scale[0]
    val transform = node.child("transform") ?: XmlNode("transform")
    val location = transform.child("location") ?: XmlNode("location", mapOf("value" to "0,0,0"))
    fun translated(value: String?): String {
        val parts = value?.split(',')?.map { it.toFloatOrNull() ?: 0f } ?: emptyList()
        val values = FloatArray(maxOf(3, parts.size)) { parts.getOrElse(it) { 0f } }
        values[0] += (along * cos(turn)).toFloat()
        values[1] += (along * sin(turn)).toFloat()
        return formatFloats(values)
    }
    val moved = location.copy(attrs = if (location["value"] != null) location.attrs + ("value" to translated(location["value"])) else location.attrs,
        children = location.children.map { if (it.name == "kf") it.with("v", translated(it["v"])) else it })
    return copy(node = node.with("wrapWidth", "$width")
        .withChild("transform", transform.withChild("location", moved)))
}
