package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.isMask
import app.absentmotion.model.leansOnBelow

internal fun previewOrder(last: Int, playhead: Int, run: Int = 1): List<Int> {
    val end = last.coerceAtLeast(0)
    val at = playhead.coerceIn(0, end)
    val step = run.coerceAtLeast(1)
    return buildList {
        var ahead = at + 1
        var behind = at - 1
        while (ahead <= end || behind >= 0) {
            repeat(2 * step) { if (ahead <= end) add(ahead++) }
            if (behind >= 0) {
                val from = maxOf(0, behind - step + 1)
                for (frame in from..behind) add(frame)
                behind = from - 1
            }
        }
    }
}

private fun XmlNode.visual(): XmlNode = copy(attrs = attrs - "label", children = children.map { it.visual() })

private fun Scene.visualLayers(): List<Layer> = layers.filter { it.kind != "audio" }

private fun Scene.visualHeader(): XmlNode = node.copy(
    attrs = node.attrs - setOf("title", "modifiedTime", "totalTime", "createdTime"),
    children = node.children.filter { it.name !in Scene.LAYER_TAGS && it.name != "bookmark" },
)

private fun Layer.drivesEverything(): Boolean = kind == "camera" || kind == "light"

private fun XmlNode.references(): Set<String> = buildSet {
    fun walk(node: XmlNode) {
        for (key in listOf("parent", "pathLayer")) node[key]?.takeIf { it != "0" }?.let(::add)
        if (node.name == "valueLink") node["layer"]?.let(::add)
        node.children.forEach(::walk)
    }
    walk(this@references)
}

private fun XmlNode.innerIds(): List<String> = children.flatMap { c -> listOfNotNull(c["id"]?.takeIf { c.name in Scene.LAYER_TAGS }) + c.innerIds() }

private fun XmlNode.followsBeats(): Boolean = children.any { c ->
    c.name == "effect" && c["hidden"] != "true" && (c["id"] == "absent.particles" || c["id"]?.startsWith("absent.beat") == true) || c.followsBeats()
}

private val Layer.readsBelow: Boolean
    get() = leansOnBelow || effects.any { it["hidden"] != "true" && it["id"] == "absent.displacementmap" }

private fun Scene.addBelowUsers(users: HashMap<String, MutableSet<String>>) {
    val list = layers
    for ((i, layer) in list.withIndex()) {
        if (!layer.readsBelow) continue
        var j = i - 1
        while (j >= 0) {
            users.getOrPut(list[j].id.toString()) { mutableSetOf() } += layer.id.toString()
            if (list[j].kind != "audio" && !list.isMask(j)) break
            j--
        }
    }
}

internal fun previewDirtyRanges(before: Scene, after: Scene): List<IntRange> {
    val whole = listOf(0..maxOf(before.lastFrame, after.lastFrame))
    if (before.visualHeader() != after.visualHeader()) return whole
    if (before.bookmarks != after.bookmarks && (before.node.followsBeats() || after.node.followsBeats())) return whole
    val old = before.visualLayers()
    val next = after.visualLayers()
    if (old.map { it.node.visual() } == next.map { it.node.visual() }) return emptyList()
    val oldIds = old.map { it.id }
    val newIds = next.map { it.id }
    if (oldIds.filter { it in newIds } != newIds.filter { it in oldIds }) return whole
    val previous = old.associateBy { it.id.toString() }
    val current = next.associateBy { it.id.toString() }
    val users = HashMap<String, MutableSet<String>>()
    for (layer in old + next) for (ref in layer.node.references()) users.getOrPut(ref) { mutableSetOf() } += layer.id.toString()
    before.addBelowUsers(users)
    after.addBelowUsers(users)
    val dirty = (previous.keys + current.keys).filter { previous[it]?.node?.visual() != current[it]?.node?.visual() }.toMutableSet()
    val queue = ArrayDeque(dirty.flatMap { id -> listOf(id) + listOfNotNull(previous[id], current[id]).flatMap { it.node.innerIds() } })
    while (queue.isNotEmpty()) users[queue.removeFirst()]?.forEach { if (dirty.add(it)) queue.addLast(it) }
    if (dirty.any { id -> listOfNotNull(previous[id], current[id]).any { it.drivesEverything() } }) return whole
    val last = maxOf(before.lastFrame, after.lastFrame)
    return dirty.flatMap { id ->
        listOfNotNull(previous[id]?.let { before to it }, current[id]?.let { after to it }).map { (scene, layer) ->
            (scene.frameAt(layer.startTime) - 1).coerceIn(0, last)..(scene.frameAt(layer.endTime) + 1).coerceIn(0, last)
        }
    }
}
