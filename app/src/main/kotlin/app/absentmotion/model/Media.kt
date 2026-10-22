package app.absentmotion.model

@JvmInline
value class MediaDeclaration(val node: XmlNode) {
    val uri: String get() = node["uri"] ?: ""
    val filename: String get() = node["filename"] ?: ""
    val title: String get() = node["title"] ?: filename
    val type: String get() = node["type"] ?: ""
    val size: Long get() = node["size"]?.toLongOrNull() ?: -1
    val orientation: Int get() = node["orientation"]?.toIntOrNull() ?: 0
    val infoUpdated: Long get() = node["infoUpdated"]?.toLongOrNull() ?: 0
    val width: Int get() = node["width"]?.toIntOrNull() ?: 0
    val height: Int get() = node["height"]?.toIntOrNull() ?: 0
    val duration: Long get() = node["duration"]?.toLongOrNull() ?: -1
    val fps: Float? get() = node["fps"]?.finiteFloat()?.takeIf { it > 0 }
}

fun exifOrientation(tag: Int): Pair<Int, Boolean> = when (tag) {
    2 -> 0 to true
    3 -> 180 to false
    4 -> 180 to true
    5 -> 270 to true
    6 -> 90 to false
    7 -> 90 to true
    8 -> 270 to false
    else -> 0 to false
}

fun fpsText(fps: Float): String = String.format(java.util.Locale.US, "%.2f", fps)

enum class MediaFillMode(val xml: String) { Fill("fill"), Fit("fit"), Stretch("stretch") }

data class MediaFill(
    val uri: String,
    val video: Boolean = false,
    val mode: MediaFillMode = MediaFillMode.Fill,
    val inTime: Int = 0,
    val outTime: Int = Int.MAX_VALUE,
    val speed: Float = 1f,
)

fun XmlNode.mediaUris(): Set<String> = buildSet {
    fun walk(n: XmlNode) {
        if (n.name != "media") {
            listOf("fillImage", "fillVideo", "src").forEach { key -> n[key]?.let { add(it) } }
            n.children.forEach(::walk)
        }
    }
    walk(this@mediaUris)
}

fun XmlNode.imageUris(): Set<String> = buildSet {
    fun walk(n: XmlNode) {
        n["fillImage"]?.let { add(it) }
        n.children.forEach(::walk)
    }
    walk(this@imageUris)
}

fun XmlNode.modelUris(): Set<String> = buildSet {
    fun walk(n: XmlNode) {
        if (n.name == "model") n["src"]?.let { add(it) }
        n.children.forEach(::walk)
    }
    walk(this@modelUris)
}

fun XmlNode.mapMediaUris(change: (String) -> String): XmlNode {
    val keys = when (name) { "media" -> listOf("uri"); "ai" -> listOf("original", "result"); else -> listOf("fillImage", "fillVideo", "src") }
    val self = keys.fold(this) { node, key -> node[key]?.let { node.with(key, change(it)) } ?: node }
    return self.copy(children = children.map { it.mapMediaUris(change) })
}

fun Scene.mediaName(uri: String): String = (media(uri)?.filename?.takeIf { it.isNotEmpty() }
    ?: (runCatching { java.net.URI(uri).path }.getOrNull() ?: uri).substringAfterLast('/').takeIf { it.isNotEmpty() } ?: "media").substringAfterLast('/')

fun Scene.replaceMedia(old: String, with: MediaDeclaration): Scene =
    Scene(node.copy(children = node.children.filterNot { it.name == "media" && it["uri"] == old })
        .mapMediaUris { if (it == old) with.uri else it }).withMedia(with)
