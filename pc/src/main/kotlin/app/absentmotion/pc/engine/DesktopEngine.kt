package app.absentmotion.pc.engine

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.videoTimeUs
import app.absentmotion.model.textSpacing
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DesktopEngine(val assets: File, private val repo: File?, private val bundledEmoji: Boolean = true) : AutoCloseable {
    private val handle = DesktopNative.create()
    val definitions: List<EffectDefinition>
    val loadErrors = linkedMapOf<String, String>()
    private val videos = linkedMapOf<String, VideoSource>()
    private var timings: Map<Long, VideoTiming> = emptyMap()
    private var buffer: ByteBuffer? = null
    private var frameImage: BufferedImage? = null
    private var scene = Scene.parse("<scene width=\"1920\" height=\"1080\" fps=\"30\" totalTime=\"10000\"/>")

    init {
        val effects = assets.resolve("effects")
        val keys = JSONArray(effects.resolve("index.json").readText())
        definitions = (0 until keys.length()).mapNotNull { index ->
            val key = keys.getString(index)
            runCatching { EffectDefinition.parse(JSONObject(effects.resolve("$key.json").readText())) }
                .onFailure { loadErrors[key] = it.message ?: "Invalid definition" }.getOrNull()
        }
        definitions.forEach { def ->
            val status = DesktopNative.loadEffect(handle, def.runtimeXml())
            if (status != "ok") loadErrors[def.key] = status
        }
    }

    fun setScene(value: Scene, base: File? = null) {
        val error = DesktopNative.setScene(handle, prepare(value.node).toXml())
        require(error == null) { error ?: "Invalid scene" }
        videos.values.forEach { it.close() }
        videos.clear()
        scene = value
        timings = value.layers.filter { it.node["fillVideo"] != null }.associate { it.id to VideoTiming(it) }
        val declared = value.media.associate { it.uri to (it.node["type"] ?: "").startsWith("video/") }
        val used = value.layers.flatMap { l -> listOfNotNull(l.node["fillImage"]?.let { it to false }, l.node["fillVideo"]?.let { it to true }) }.toMap()
        (used + declared).forEach { (uri, video) ->
            val file = resolve(uri, base) ?: return@forEach
            if (video) {
                val ffmpeg = DesktopPlatform.executable("ffmpeg")
                val ffprobe = DesktopPlatform.executable("ffprobe")
                runCatching { videos[uri] = FfmpegVideoSource(file, ffmpeg, ffprobe) }
            } else DesktopNative.setPicture(handle, uri, file.absolutePath)
        }
    }

    private fun resolve(uri: String, base: File?): File? = runCatching {
        val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
        listOfNotNull(file.takeIf { it.isAbsolute }, base?.resolve(uri), repo?.resolve(uri)).firstOrNull { it.isFile }
    }.getOrNull()

    private val emojis = app.absentmotion.fonts.emojiPaths(bundledEmoji).let { (a, b) -> (a ?: assets.resolve("emoji/NotoColorEmoji.ttf").absolutePath) to (b ?: assets.resolve("emoji/NotoColorEmojiFlags.ttf").absolutePath) }

    private fun prepare(node: XmlNode): XmlNode {
        val children = node.children.map(::prepare)
        if (node.name != "text") return node.copy(children = children)
        val font = node["font"].orEmpty()
        val parts = font.substringAfter('?', "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to java.net.URLDecoder.decode(p[1], Charsets.UTF_8) } }.toMap()
        val family = parts["name"] ?: "Roboto"
        val weight = parts["weight"]?.toIntOrNull() ?: 400
        val italic = parts["italic"] == "1"
        val folder = assets.resolve("fonts").resolve(family.replace(" ", ""))
        val files = folder.listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }.orEmpty()
        val imported = if (font.startsWith("imported?")) parts["name"]?.let { name -> DesktopPlatform.dataDir.resolve("fonts/imported").resolve(File(name).name).takeIf { it.isFile } } else null
        val selected = imported ?: files.minByOrNull { f ->
            val name = f.name.lowercase()
            (if (italic == ("italic" in name)) 0 else 4) + (if ((weight >= 600) == ("bold" in name)) 0 else 2)
        } ?: assets.resolve("fonts/Roboto/Roboto-Regular.ttf")
        val layer = Layer(node)
        return node.copy(children = children).with("_fontPath", selected.absolutePath).with("_fontFamily", family)
            .with("_fontWeight", "$weight").with("_fontItalic", "$italic")
            .with("_letterSpacing", "${layer.textSpacing("letterspacing", 0f)}")
            .with("_lineSpacing", "${layer.textSpacing("linespacing", 1f)}")
            .with("_emoji", emojis.first).with("_emojiFlags", emojis.second)
    }

    fun render(time: Int, width: Int, height: Int): BufferedImage {
        videos.forEach { (uri, source) ->
            val layer = scene.layers.firstOrNull { it.node["fillVideo"] == uri && time in it.startTime..it.endTime }
            if (layer != null) {
                val sourceTime = layer.videoTimeUs(time, scene, timings.getValue(layer.id)).coerceAtLeast(0) / 1000
                source.frame(sourceTime)?.let { DesktopNative.setFrame(handle, uri, it.width, it.height, it.rgba) }
            }
        }
        val byteCount = width * height * 4
        val output = buffer?.takeIf { it.capacity() == byteCount } ?: ByteBuffer.allocateDirect(byteCount).also { buffer = it }
        output.clear()
        check(DesktopNative.render(handle, time.toDouble(), width, height, output))
        val image = frameImage?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE).also { frameImage = it }
        val pixels = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        output.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
        return image
    }

    fun hitTest(x: Float, y: Float, time: Int) = DesktopNative.hitTest(handle, x, y, time.toDouble())
    fun quad(id: Long, time: Int) = DesktopNative.layerQuad(handle, id, time.toDouble())
    override fun close() { videos.values.forEach { it.close() }; DesktopNative.destroy(handle) }
}
