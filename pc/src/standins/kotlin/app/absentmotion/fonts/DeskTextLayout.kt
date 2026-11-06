package app.absentmotion.fonts

import app.absentmotion.model.*

fun FontLibrary.prepareText(xml: String, timeMs: Int = 0, parsed: Scene? = null): String {
    val scene = parsed ?: Scene.parse(xml)
    if (!hasText(scene.node)) return xml
    return prepareText(scene.node, scene.fps, timeMs).toXml()
}

private fun hasText(scene: XmlNode): Boolean = scene.children.any { it.name == "text" || it.child("scene")?.let(::hasText) == true }

private fun FontLibrary.prepareText(scene: XmlNode, fps: Int, timeMs: Int): XmlNode {
    val (emoji, flags) = emojiPaths(androidEmoji())
    return scene.mapTimedLayers(fps, timeMs.toDouble()) { raw, localFps, localTime ->
        val node = raw.node
        if (node.name != "text") return@mapTimedLayers node
        val layer = raw.at(localTime.toInt(), localFps)
        val name = FontName.parse(layer.font)
        node.with("_fontPath", (renderFile(layer.font) ?: android.content.AssetManager.root().resolve("fonts/Roboto/Roboto-Regular.ttf")).absolutePath)
            .with("_fontFamily", name.family).with("_fontIndex", "0")
            .with("_letterSpacing", "${layer.textSpacing("letterspacing", 0f)}").with("_lineSpacing", "${layer.textSpacing("linespacing", 1f)}")
            .with("_fontWeight", "${layer.fontWeight?.let { Math.round(it) } ?: name.weight}").with("_fontAxes", if (layer.fontWeight != null) "true" else null).with("_fontItalic", "${name.italic}").with("_emoji", emoji).with("_emojiFlags", flags)
    }
}

fun emojiPaths(bundled: Boolean, system: java.io.File? = app.absentmotion.desk.DeskOs.systemEmoji): Pair<String?, String?> {
    val root = android.content.AssetManager.root()
    val noto = root.resolve("emoji/NotoColorEmoji.ttf").takeIf { it.isFile }
    val flags = root.resolve("emoji/NotoColorEmojiFlags.ttf").takeIf { it.isFile }
    return if (!bundled && system != null) system.absolutePath to flags?.absolutePath.takeUnless { app.absentmotion.desk.DeskOs.mac }
    else noto?.absolutePath to flags?.absolutePath
}
