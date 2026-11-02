package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import java.util.concurrent.locks.LockSupport

fun shapeNode(
    kind: String = "shape",
    attrs: Map<String, String> = emptyMap(),
    children: List<XmlNode> = emptyList(),
    id: Long = 1,
    start: Int = 0,
    end: Int = 2000,
    parent: Long? = null,
): XmlNode = XmlNode(
    kind,
    linkedMapOf("id" to "$id", "startTime" to "$start", "endTime" to "$end") + attrs + (parent?.let { mapOf("parent" to "$it") } ?: emptyMap()),
    children,
)

fun shapeLayer(
    kind: String = "shape",
    attrs: Map<String, String> = emptyMap(),
    children: List<XmlNode> = emptyList(),
    id: Long = 1,
    start: Int = 0,
    end: Int = 2000,
    parent: Long? = null,
): Layer = Layer(shapeNode(kind, attrs, children, id, start, end, parent))

fun videoNode(
    start: Int,
    end: Int,
    id: Long = 1,
    inTime: Int? = 500,
    outTime: Int? = null,
    speed: String? = null,
    uri: String = "content://v",
    attrs: Map<String, String> = emptyMap(),
    children: List<XmlNode> = emptyList(),
): XmlNode = shapeNode(
    id = id,
    start = start,
    end = end,
    attrs = linkedMapOf("fillType" to "media", "fillVideo" to uri) +
        listOfNotNull(inTime?.let { "inTime" to "$it" }, outTime?.let { "outTime" to "$it" }, speed?.let { "speed" to it }) + attrs,
    children = children,
)

fun videoLayer(
    start: Int,
    end: Int,
    id: Long = 1,
    inTime: Int? = 500,
    outTime: Int? = null,
    speed: String? = null,
    uri: String = "content://v",
    attrs: Map<String, String> = emptyMap(),
    children: List<XmlNode> = emptyList(),
): Layer = Layer(videoNode(start, end, id, inTime, outTime, speed, uri, attrs, children))

fun sceneOf(vararg children: XmlNode, width: Int = 400, height: Int = 400, fps: Int = 30): Scene =
    Scene(Scene.create("t", width, height, fps, 0).node.copy(children = children.toList()))

fun sceneXml(vararg layers: String): Scene = Scene.parse("""<scene width="100" height="100" fps="30">${layers.joinToString("")}</scene>""")

fun awaitCondition(what: String, timeoutMs: Long = 60_000, poll: () -> Boolean) {
    val until = System.nanoTime() + timeoutMs * 1_000_000L
    while (!poll()) {
        if (System.nanoTime() >= until) throw AssertionError("timed out waiting for $what")
        LockSupport.parkNanos(1_000_000L)
    }
}
