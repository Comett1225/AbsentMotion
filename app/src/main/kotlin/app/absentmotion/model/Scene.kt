package app.absentmotion.model

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

@JvmInline
value class Scene(val node: XmlNode) {
    val title: String get() = node["title"] ?: ""
    val width: Int get() = node["width"]?.toIntOrNull() ?: 1080
    val height: Int get() = node["height"]?.toIntOrNull() ?: 1080
    val fps: Int get() = node["fps"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30
    val bgColor: Int get() = parseColor(node["bgcolor"]) ?: 0
    val totalTime: Int get() = node["totalTime"]?.toIntOrNull() ?: 0

    val isElement: Boolean get() = node["type"] == "element"

    val precompose: String get() = node["precompose"] ?: "dynamicResolution"

    val retime: String get() = node["retime"] ?: "freeze"

    val retimeIn: Int get() = node["retimeIn"]?.toIntOrNull() ?: 0
    val retimeOut: Int get() = node["retimeOut"]?.toIntOrNull() ?: 0

    val layers: List<Layer> get() = node.children.filter { it.name in LAYER_TAGS }.map { Layer(it) }

    val media: List<MediaDeclaration> get() = node.children.filter { it.name == "media" }.map(::MediaDeclaration)

    fun media(uri: String): MediaDeclaration? = media.firstOrNull { it.uri == uri }
    fun isMissing(uri: String): Boolean = media(uri)?.node?.get("missing") == "true"

    val bookmarks: List<Int> get() = node.children.filter { it.name == "bookmark" }.mapNotNull { it["t"]?.toIntOrNull() }.sorted()

    fun withBookmarkToggled(frame: Int): Scene {
        val here = { n: XmlNode -> n.name == "bookmark" && n["t"]?.toIntOrNull()?.let { frameAt(it) == frame } == true }
        return if (node.children.any(here)) Scene(node.copy(children = node.children.filterNot(here)))
        else withBookmarks(listOf(frameStart(frame)))
    }

    fun withBookmarks(times: List<Int>): Scene {
        val added = times.distinct().filter { it !in bookmarks }.sorted().map { XmlNode("bookmark", mapOf("t" to "$it")) }
        if (added.isEmpty()) return this
        val kept = node.children.filter { it.name != "bookmark" }
        val all = (node.children.filter { it.name == "bookmark" } + added).sortedBy { it["t"]?.toIntOrNull() ?: 0 }
        val index = kept.indexOfFirst { it.name in LAYER_TAGS }.let { if (it < 0) kept.size else it }
        return Scene(node.copy(children = kept.take(index) + all + kept.drop(index)))
    }

    fun withMedia(media: MediaDeclaration): Scene {
        if (this.media(media.uri) != null) return this
        val index = node.children.indexOfFirst { it.name == "bookmark" || it.name in LAYER_TAGS }.let { if (it < 0) node.children.size else it }
        return Scene(node.copy(children = node.children.toMutableList().also { it.add(index, media.node) }))
    }

    fun layer(id: Long): Layer? = layers.firstOrNull { it.id == id }

    fun layerAt(id: Long, timeMs: Int): Layer? = layer(id)?.at(timeMs, fps)

    fun frameStart(frame: Int): Int = (frame * 1000L / fps).toInt()
    fun frameAt(timeMs: Int): Int = (((timeMs + 1L) * fps + 999) / 1000 - 1).toInt()
    fun frameTime(frame: Int): Int = ((2 * frame + 1) * 500L / fps).toInt()
    fun frameEnd(frame: Int): Int = frameStart(frame + 1) - 1
    fun firstShownFrame(start: Int): Int = frameAt(start).let { if (frameTime(it) < start) it + 1 else it }
    fun lastShownFrame(end: Int): Int = frameAt(end).let { if (frameTime(it) > end) it - 1 else it }
    fun showsAnyFrame(start: Int, end: Int): Boolean = firstShownFrame(start) <= lastShownFrame(end)
    val lastFrame: Int get() = frameAt(totalTime)

    fun withTitle(title: String) = Scene(node.with("title", title))

    fun withSettings(width: Int, height: Int, fps: Int, bgColor: Int) = Scene(
        node.with("width", "$width").with("height", "$height")
            .with("exportWidth", "$width").with("exportHeight", "$height")
            .with("fps", "$fps").with("bgcolor", formatColor(bgColor)),
    )

    fun update(id: Long, timeMs: Int? = null, change: (Layer) -> Layer): Scene = withChildren(
        node.children.map {
            if (it.name !in LAYER_TAGS || Layer(it).id != id) it
            else change(if (timeMs == null) Layer(it) else Layer(it).at(timeMs, fps)).node
        },
    )

    fun add(layer: Layer, media: List<MediaDeclaration> = emptyList()): Scene {
        var next = media.fold(this) { scene, record -> scene.withMedia(record) }
        layer.node.mediaUris().forEach { uri ->
            next = next.withMedia(MediaDeclaration(XmlNode("media", mapOf("uri" to uri))))
        }
        return next.withChildren(next.node.children + layer.node)
    }

    fun remove(id: Long): Scene = withChildren(node.children.filterNot { it.name in LAYER_TAGS && Layer(it).id == id })

    fun move(id: Long, index: Int): Scene {
        val moving = layer(id) ?: return this
        val others = layers.filterNot { it.id == id }.toMutableList()
        others.add(index.coerceIn(0, others.size), moving)
        return withChildren(node.children.filterNot { it.name in LAYER_TAGS } + others.map { it.node })
    }

    private fun withChildren(children: List<XmlNode>): Scene {
        val end = children.filter { it.name in LAYER_TAGS }.maxOfOrNull { Layer(it).endTime } ?: 0
        val removed = node.mediaUris() - node.copy(children = children).mediaUris()
        return Scene(node.copy(children = children.filterNot { it.name == "media" && it["uri"] in removed }).with("totalTime", "$end"))
    }

    fun toXml(): String = node.with("modifiedTime", "${System.currentTimeMillis()}").toXml()

    companion object {
        val LAYER_TAGS = setOf("shape", "text", "drawing", "embedScene", "camera", "audio", "nullobj", "model")

        fun parse(xml: String) = Scene(XmlNode.parse(xml))

        fun create(title: String, width: Int, height: Int, fps: Int, bgColor: Int) = Scene(
            XmlNode(
                "scene",
                linkedMapOf(
                    "title" to title, "width" to "$width", "height" to "$height",
                    "exportWidth" to "$width", "exportHeight" to "$height", "precompose" to "dynamicResolution",
                    "bgcolor" to formatColor(bgColor), "totalTime" to "0", "fps" to "$fps",
                    "amver" to "1002592", "ffver" to "106",
                    "am" to "app.absentmotion", "amplatform" to "android", "retime" to "freeze", "retimeAdaptFPS" to "false",
                ),
            ),
        )
    }
}

enum class LabelColour(val id: String, val start: Int, val end: Int) {
    Ruby("+ruby", 0xFFE0314B.toInt(), 0xFF9E1B32.toInt()), Red("+red", 0xFFFF6B5E.toInt(), 0xFFD93A2E.toInt()),
    Orange("+orange", 0xFFFFA64D.toInt(), 0xFFE07A1F.toInt()), Yellow("+yellow", 0xFFFFD84D.toInt(), 0xFFE8B80C.toInt()),
    Green("+green", 0xFF5FD38D.toInt(), 0xFF2E9E5B.toInt()), Blue("+blue", 0xFF4FB3F6.toInt(), 0xFF2480D6.toInt()),
    DarkBlue("+darkblue", 0xFF3D5AE0.toInt(), 0xFF1F2F8F.toInt()), Purple("+purple", 0xFFA46BF0.toInt(), 0xFF6F3BC4.toInt()),
    Magenta("+magenta", 0xFFEC5FC2.toInt(), 0xFFB5278F.toInt()), Gray("+gray", 0xFFD4D7DD.toInt(), 0xFF9096A0.toInt()),
    Black("+black", 0xFF5B6070.toInt(), 0xFF2A2D38.toInt()),
}

data class Layer(val node: XmlNode, val t: Float = 0f, val snap: Float = 0f) {
    val mediaFill: MediaFill? get() {
        if (fillType != "media") return null
        val uri = node["fillImage"] ?: node["fillVideo"] ?: return null
        return MediaFill(uri, node["fillImage"] == null,
            MediaFillMode.entries.firstOrNull { it.xml == node["mediaFillMode"] } ?: MediaFillMode.Fill,
            node["inTime"]?.toIntOrNull() ?: 0, node["outTime"]?.toIntOrNull() ?: Int.MAX_VALUE,
            node["speed"]?.toFloatOrNull() ?: 1f)
    }

    val id: Long get() = node["id"]?.toLongOrNull() ?: 0
    val kind: String get() = node.name

    val link: String? get() = node["link"]

    val nested: Scene? get() = if (kind == "embedScene") node.child("scene")?.let(::Scene) else null
    val label: String get() = node["label"] ?: if (kind == "text") content.ifEmpty { "Text" } else ""
    val shape: String get() = node["s"] ?: ""
    val startTime: Int get() = node["startTime"]?.toIntOrNull() ?: 0
    val endTime: Int get() = node["endTime"]?.toIntOrNull() ?: 0
    val hidden: Boolean get() = node["hidden"] == "true"
    val fillType: String get() = node["fillType"] ?: "none"
    val fillColor: Int get() = parseColor(element(PropRef.FillColor)?.valueAt(t)) ?: 0xFFFFFFFF.toInt()

    val location: FloatArray get() = transform("location", 0f, 0f, 0f)
    val pivot: FloatArray get() = transform("pivot", 0f, 0f, 0f)
    val scale: FloatArray get() = transform("scale", 1f, 1f)
    val opacity: Float get() = transform("opacity", 1f)[0]

    val rotation: Float get() = -transform("rotation", 0f)[0]

    val skew: FloatArray get() = transform("skew", 0f, 0f).let { floatArrayOf(Math.toDegrees(it[0].toDouble()).toFloat(), Math.toDegrees(it[1].toDouble()).toFloat()) }

    val tilt: FloatArray get() = transform("tilt", 0f, 0f)

    fun transform(name: String, vararg defaults: Float): FloatArray =
        floats(node.child("transform")?.child(name)?.valueAt(t), defaults)

    fun propText(name: String): String? = element(PropRef.shape(name))?.valueAt(t)

    fun prop(name: String): FloatArray? = propText(name)?.let { floats(it, FloatArray(0)) }

    fun activeAt(timeMs: Int): Boolean = !hidden && timeMs in startTime..endTime

    fun at(timeMs: Int, fps: Int): Layer {
        val length = (endTime - startTime).coerceAtLeast(1).toFloat()
        return copy(t = (timeMs - startTime) / length, snap = 500f / fps / length)
    }

    fun timeOf(time: Float): Int = (startTime + time * (endTime - startTime)).roundToInt()

    fun withLabel(label: String) = copy(node = node.with("label", label))

    val labelColour: LabelColour? get() = LabelColour.entries.firstOrNull { it.id == node["tag"] }

    fun withLabelColour(colour: LabelColour?) = copy(node = node.with("tag", colour?.id))
    fun withHidden(hidden: Boolean) = copy(node = node.with("hidden", if (hidden) "true" else null))
    fun withId(id: Long) = copy(node = node.with("id", "$id"))

    fun withTimes(startTime: Int, endTime: Int) = copy(
        node = node.retimed(this.startTime, this.endTime, startTime, endTime).with("startTime", "$startTime").with("endTime", "$endTime"),
    )

    fun movedTo(startTime: Int) = copy(node = node.with("startTime", "$startTime").with("endTime", "${startTime + endTime - this.startTime}"))

    fun withFill(type: String, color: Int = fillColor) =
        copy(node = node.with("fillType", type)).withValue(PropRef.FillColor, formatColor(color))

    fun withTransform(name: String, vararg values: Float) = withValue(PropRef(PropRef.Kind.Transform, name), formatFloats(values))

    fun withRotation(degrees: Float) = withTransform("rotation", -degrees)

    fun withSkew(x: Float, y: Float) = withTransform("skew", Math.toRadians(x.toDouble()).toFloat(), Math.toRadians(y.toDouble()).toFloat())

    fun withTilt(x: Float, y: Float) = withTransform("tilt", x, y)

    fun withProp(name: String, type: String, value: String): Layer {
        val ref = PropRef.shape(name)
        return if (element(ref) != null) withValue(ref, value)
        else copy(node = node.copy(children = node.children + XmlNode("property", linkedMapOf("name" to name, "type" to type, "value" to value))))
    }

    fun withProp(name: String, type: String, vararg values: Float): Layer = withProp(name, type, formatFloats(values))

    fun element(ref: PropRef): XmlNode? = when (ref.kind) {
        PropRef.Kind.Effect -> effects.getOrNull(ref.effectIndex)?.effectProperty(ref.name)
        PropRef.Kind.Transform -> node.child("transform")?.child(ref.name)
        PropRef.Kind.Child -> node.path(ref.name)
        PropRef.Kind.Property -> node.children.firstOrNull { it.name == "property" && it["name"] == ref.name }
    }

    private fun withElement(ref: PropRef, element: XmlNode): Layer = copy(
        node = when (ref.kind) {
            PropRef.Kind.Effect -> withEffect(ref.effectIndex) { effect ->
                val old = effect.effectProperty(ref.name)
                val value = element.with("name", ref.name).copy(name = "property")
                effect.copy(children = if (old == null) effect.children + value else effect.children.map { if (it === old) value else it })
            }.node
            PropRef.Kind.Transform -> node.withChild("transform", (node.child("transform") ?: XmlNode("transform")).withChild(ref.name, element))
            PropRef.Kind.Child -> node.withPath(ref.name, element)
            PropRef.Kind.Property -> node.copy(children = node.children.map { if (it.name == "property" && it["name"] == ref.name) element else it })
        },
    )

    fun withValue(ref: PropRef, value: String): Layer =
        withElement(ref, (element(ref) ?: XmlNode(ref.name)).withValueAt(t, value, snap))

    fun keyframes(ref: PropRef): List<Keyframe> = element(ref)?.keyframes ?: emptyList()

    fun onKeyframe(ref: PropRef): Boolean = (element(ref)?.keyframeAt(t, snap) ?: -1) >= 0

    fun withKeyframeToggled(ref: PropRef, fallback: String): Layer {
        val element = element(ref) ?: XmlNode(ref.name, mapOf("value" to fallback))
        return withElement(ref, element.withKeyframeToggled(t, snap))
    }

    fun easing(ref: PropRef): Easing? {
        val element = element(ref) ?: return null
        val index = element.stretchAt(t, snap)
        return if (index < 0) null else Easing.parse(element.keyframes[index].e)
    }

    fun stretchProgress(ref: PropRef): Float? {
        val element = element(ref) ?: return null
        val index = element.stretchAt(t, snap)
        if (index < 0) return null
        val keys = element.keyframes
        return (t - keys[index - 1].t) / (keys[index].t - keys[index - 1].t)
    }

    fun withEasing(ref: PropRef, easing: Easing): Layer {
        val element = element(ref) ?: return this
        val index = element.stretchAt(t, snap)
        if (index < 0) return this
        val text = easing.takeUnless { it.isLinear }?.toString()
        return withElement(ref, element.withKeyframes(element.keyframes.toMutableList().also { it[index] = it[index].copy(e = text) }))
    }

    fun withEasingEverywhere(ref: PropRef, easing: Easing): Layer {
        val element = element(ref) ?: return this
        val text = easing.takeUnless { it.isLinear }?.toString()
        return withElement(ref, element.withKeyframes(element.keyframes.mapIndexed { i, k -> if (i == 0) k else k.copy(e = text) }))
    }

    fun keyframeTimes(): List<Int> {
        val out = sortedSetOf<Int>()
        fun walk(n: XmlNode) {
            if (n.name == "speedMap") {
                val timing = VideoTiming(this)
                n.keyframes.forEach { out.add(startTime + timing.localMs(it.t.toDouble() / 1_000_000).roundToInt()) }
            } else if (n.name == "kf") n["t"]?.toFloatOrNull()?.let { out.add(timeOf(it)) }
            else if (n.name != "scene") n.children.forEach(::walk)
        }
        walk(node)
        return out.toList()
    }

    companion object {
        fun newId(): Long = Random.nextLong(10_000_000, 99_999_999)
    }
}

internal fun floats(text: String?, defaults: FloatArray): FloatArray {
    val parsed = text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
    return FloatArray(maxOf(parsed.size, defaults.size)) { parsed.getOrNull(it) ?: defaults[it] }
}

fun formatFloats(values: FloatArray): String = values.joinToString(",") { String.format(Locale.US, "%.6f", it) }

fun parseColor(text: String?): Int? = text?.removePrefix("#")?.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()

fun formatColor(argb: Int): String = String.format(Locale.US, "#%08x", argb)
