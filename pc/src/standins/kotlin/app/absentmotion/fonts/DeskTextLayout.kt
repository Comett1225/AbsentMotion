package app.absentmotion.fonts

import app.absentmotion.model.*

fun FontLibrary.prepareText(xml: String, timeMs: Int = 0): String {
    val scene = Scene.parse(xml)
    if (!hasText(scene.node)) return xml
    return prepareText(scene.node, scene.fps, timeMs).toXml()
}

private fun hasText(scene: XmlNode): Boolean = scene.children.any { it.name == "text" || it.child("scene")?.let(::hasText) == true }

private fun FontLibrary.prepareText(scene: XmlNode, fps: Int, timeMs: Int): XmlNode {
    val emoji = (emojiFile() ?: android.content.AssetManager.root().resolve("emoji/NotoColorEmoji.ttf").takeIf { it.isFile })?.absolutePath
    val flags = (emojiFile(flags = true) ?: android.content.AssetManager.root().resolve("emoji/NotoColorEmojiFlags.ttf").takeIf { it.isFile })?.absolutePath
    return scene.mapTimedLayers(fps, timeMs.toDouble()) { raw, localFps, localTime ->
        val node = raw.node
        if (node.name != "text") return@mapTimedLayers node
        val layer = raw.at(localTime.toInt(), localFps)
        val name = FontName.parse(layer.font)
        node.with("_fontPath", (renderFile(layer.font) ?: android.content.AssetManager.root().resolve("fonts/Roboto/Roboto-Regular.ttf")).absolutePath)
            .with("_fontFamily", name.family).with("_fontIndex", "0")
            .with("_letterSpacing", "${layer.textSpacing("letterspacing", 0f)}").with("_lineSpacing", "${layer.textSpacing("linespacing", 1f)}")
            .with("_fontWeight", "${name.weight}").with("_fontItalic", "${name.italic}").with("_emoji", emoji).with("_emojiFlags", flags)
    }
}
