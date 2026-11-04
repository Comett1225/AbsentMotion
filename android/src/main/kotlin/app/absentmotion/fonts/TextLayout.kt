package app.absentmotion.fonts

import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.LocaleList
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.absentmotion.model.*

private class PreparedScene(val xml: String, val scene: Scene?, val texts: List<XmlNode>, val out: String)

private data class TextInput(val node: XmlNode, val letterSpacing: Float, val lineSpacing: Float, val weight: Int?, val face: Typeface,
                             val file: String?, val index: Int, val emoji: String?, val flags: String?, val locales: LocaleList)

@Volatile private var lastPrepared: PreparedScene? = null

private val preparedTexts = object : LinkedHashMap<TextInput, XmlNode>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextInput, XmlNode>) = size > 128
}

fun FontLibrary.prepareText(xml: String, timeMs: Int = 0): String {
    val last = lastPrepared?.takeIf { it.xml === xml }
    val scene = if (last != null) last.scene else Scene.parse(xml).takeIf { hasText(it.node) }
    if (scene == null) {
        lastPrepared = PreparedScene(xml, null, emptyList(), xml)
        return xml
    }
    val texts = ArrayList<XmlNode>()
    val node = prepareText(scene.node, scene.fps, timeMs, texts)
    if (last != null && last.texts.size == texts.size && texts.indices.all { last.texts[it] === texts[it] }) return last.out
    return node.toXml().also { lastPrepared = PreparedScene(xml, scene, texts, it) }
}

private const val UNWRAPPED = 1 shl 24

private fun hasText(scene: XmlNode): Boolean = scene.children.any { it.name == "text" || it.child("scene")?.let(::hasText) == true }

private fun FontLibrary.prepareText(scene: XmlNode, fps: Int, timeMs: Int, texts: MutableList<XmlNode>): XmlNode {
    val emoji = emojiFile()?.absolutePath
    val flags = emojiFile(flags = true)?.absolutePath
    return scene.mapTimedLayers(fps, timeMs.toDouble()) { raw, localFps, localTime ->
        val node = raw.node
        if (node.name != "text") return@mapTimedLayers node
        val layer = raw.at(localTime.toInt(), localFps)
        val weight = layer.fontWeight?.let { Math.round(it) }
        val face = typeface(layer.font)
        val file = renderFile(layer.font)?.takeUnless { weight == null && it.path.startsWith("/system/") && systemFamily(layer.font) != null }?.absolutePath
        val input = TextInput(node, layer.textSpacing("letterspacing", 0f), layer.textSpacing("linespacing", 1f), weight, face, file,
            fontIndex(layer.font), emoji, flags, LocaleList.getDefault())
        (synchronized(preparedTexts) { preparedTexts[input] } ?: layoutText(input, layer).also { synchronized(preparedTexts) { preparedTexts[input] = it } })
            .also(texts::add)
    }
}

private fun FontLibrary.layoutText(input: TextInput, layer: Layer): XmlNode {
    val node = input.node
    val name = FontName.parse(layer.font)
    val weight = input.weight
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = layer.textSize.coerceAtLeast(0.01f) * 3f
        typeface = input.face
        letterSpacing = input.letterSpacing
        if (weight != null) setFontVariationSettings("'wght' $weight")
    }
    val alignment = when (layer.textAlign) {
        "center" -> Layout.Alignment.ALIGN_CENTER
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
    val layout = StaticLayout.Builder.obtain(layer.content, 0, layer.content.length, paint, if (layer.onTextPath) UNWRAPPED else layer.wrapWidth.coerceAtLeast(1))
        .setAlignment(alignment).setIncludePad(true).setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setLineSpacing(0f, input.lineSpacing).build()
    val lines = List(layout.lineCount) { index ->
        val raw = layer.content.substring(layout.getLineStart(index), layout.getLineEnd(index))
        val line = if (raw.lastOrNull()?.isWhitespace() == true) raw.dropLast(1) else raw
        val spaces = line.count { it == ' ' }
        val spacing = if (layer.textAlign == "justify" && index < layout.lineCount - 1 && !raw.endsWith('\n') && spaces > 0)
            (layer.wrapWidth - paint.measureText(line)) / spaces else 0f
        XmlNode("_line", mapOf("baseline" to "${layout.getLineBaseline(index)}", "rtl" to "${layout.getParagraphDirection(index) < 0}",
            "wordSpacing" to "$spacing"), text = line)
    }
    return node.with("_fontPath", input.file).with("_fontFamily", systemFamily(layer.font) ?: name.family)
        .with("_fontIndex", "${input.index}")
        .with("_letterSpacing", "${paint.letterSpacing}").with("_lineSpacing", "${input.lineSpacing}")
        .with("_fontWeight", "${weight ?: name.weight}").with("_fontAxes", if (weight != null) "true" else null).with("_fontItalic", "${name.italic}").with("_emoji", input.emoji).with("_emojiFlags", input.flags)
        .withChild("_layout", XmlNode("_layout", mapOf("height" to "${layout.height}"), lines))
}
