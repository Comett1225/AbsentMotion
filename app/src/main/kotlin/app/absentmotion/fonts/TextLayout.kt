package app.absentmotion.fonts

import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.absentmotion.model.*

fun FontLibrary.prepareText(xml: String, timeMs: Int = 0): String {
    val scene = Scene.parse(xml)
    if (!hasText(scene.node)) return xml
    return prepareText(scene.node, scene.fps, timeMs).toXml()
}

private fun hasText(scene: XmlNode): Boolean = scene.children.any { it.name == "text" || it.child("scene")?.let(::hasText) == true }

private fun FontLibrary.prepareText(scene: XmlNode, fps: Int, timeMs: Int): XmlNode {
    return scene.mapTimedLayers(fps, timeMs.toDouble()) { raw, localFps, localTime ->
        val node = raw.node
        if (node.name != "text") return@mapTimedLayers node
        val layer = raw.at(localTime.toInt(), localFps)
        val name = FontName.parse(layer.font)
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = layer.textSize.coerceAtLeast(0.01f) * 3f
            typeface = typeface(layer.font)
            letterSpacing = layer.textSpacing("letterspacing", 0f)
        }
        val alignment = when (layer.textAlign) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(layer.content, 0, layer.content.length, paint, layer.wrapWidth.coerceAtLeast(1))
            .setAlignment(alignment).setIncludePad(true).setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(0f, layer.textSpacing("linespacing", 1f)).build()
        val lines = List(layout.lineCount) { index ->
            val raw = layer.content.substring(layout.getLineStart(index), layout.getLineEnd(index))
            val line = if (raw.lastOrNull()?.isWhitespace() == true) raw.dropLast(1) else raw
            val spaces = line.count { it == ' ' }
            val spacing = if (layer.textAlign == "justify" && index < layout.lineCount - 1 && !raw.endsWith('\n') && spaces > 0)
                (layer.wrapWidth - paint.measureText(line)) / spaces else 0f
            XmlNode("_line", mapOf("baseline" to "${layout.getLineBaseline(index)}", "rtl" to "${layout.getParagraphDirection(index) < 0}",
                "wordSpacing" to "$spacing"), text = line)
        }
        node.with("_fontPath", localFile(layer.font)?.takeUnless { it.path.startsWith("/system/") && systemFamily(layer.font) != null }?.absolutePath).with("_fontFamily", systemFamily(layer.font) ?: name.family)
            .with("_fontIndex", "${fontIndex(layer.font)}")
            .with("_letterSpacing", "${paint.letterSpacing}").with("_lineSpacing", "${layer.textSpacing("linespacing", 1f)}")
            .with("_fontWeight", "${name.weight}").with("_fontItalic", "${name.italic}")
            .withChild("_layout", XmlNode("_layout", mapOf("height" to "${layout.height}"), lines))
    }
}
