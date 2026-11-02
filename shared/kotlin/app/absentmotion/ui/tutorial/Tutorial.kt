package app.absentmotion.ui.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import app.absentmotion.library.AppSettings

object Tutorial {
    const val ASK = 1
    const val NEVER = 2

    internal var tour by mutableStateOf<Tour?>(null)
    internal var request by mutableStateOf<Boolean?>(null)
    internal var offerDismissed by mutableStateOf(false)
    internal var listener: ((Boolean) -> Unit)? = null
    internal val anchors = mutableStateMapOf<String, Rect>()
    internal var shut by mutableStateOf<List<Area>?>(null)
    internal val regions = mutableStateMapOf<String, () -> List<Rect>>()
    private val owners = HashMap<String, Any>()
    var fontScale: Float? = null

    val running: Boolean get() = tour != null

    fun start(resume: Boolean) { request = resume }

    fun firstRunFinished(settings: AppSettings) {
        if (settings.tutorialAsk == 0) settings.tutorialAsk = ASK
    }

    fun pickerClosed(picked: Boolean) { listener?.invoke(picked) }

    internal fun place(id: String, owner: Any, bounds: Rect) {
        if (bounds.width <= 0f || bounds.height <= 0f) return forget(id, owner)
        owners[id] = owner
        if (anchors[id] != bounds) anchors[id] = bounds
    }

    internal fun forget(id: String, owner: Any) {
        if (owners[id] !== owner) return
        owners.remove(id)
        anchors.remove(id)
        regions.remove(id)
    }

    internal fun region(id: String, owner: Any, bounds: () -> List<Rect>) {
        owners[id] = owner
        regions[id] = bounds
    }

    internal fun bounds(id: String): List<Rect> = anchors[id]?.let { listOf(it) } ?: regions[id]?.invoke().orEmpty().filter { it.width > 0f && it.height > 0f }
}

fun Modifier.tutorialTarget(id: String?): Modifier = if (id == null || Tutorial.tour == null) this else this then TargetElement(id)

fun Modifier.tutorialGuard(any: Boolean = false): Modifier = if (Tutorial.tour == null) this else this then GuardElement(any)

fun Modifier.tutorialRegion(id: String, local: () -> List<Rect>): Modifier = if (Tutorial.tour == null) this else this then RegionElement(id, local)

private class RegionElement(val id: String, val local: () -> List<Rect>) : ModifierNodeElement<RegionNode>() {
    override fun create() = RegionNode(id, local)

    override fun update(node: RegionNode) {
        node.local = local
        if (node.id == id) return
        Tutorial.forget(node.id, node)
        node.id = id
        node.publish()
    }

    override fun equals(other: Any?) = other is RegionElement && other.id == id && other.local === local

    override fun hashCode() = 31 * id.hashCode() + System.identityHashCode(local)
}

private class RegionNode(var id: String, var local: () -> List<Rect>) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var origin by mutableStateOf<Offset?>(null)
    private val provider: () -> List<Rect> = { origin?.let { o -> local().map { it.translate(o) } }.orEmpty() }

    fun publish() = Tutorial.region(id, this, provider)

    override fun onAttach() = publish()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val at = coordinates.positionInWindow()
        if (origin != at) origin = at
    }

    override fun onDetach() = Tutorial.forget(id, this)
}

private data class TargetElement(val id: String) : ModifierNodeElement<TargetNode>() {
    override fun create() = TargetNode(id)

    override fun update(node: TargetNode) {
        if (node.id == id) return
        Tutorial.forget(node.id, node)
        node.id = id
    }
}

private class TargetNode(var id: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) Tutorial.place(id, this, coordinates.boundsInWindow())
    }

    override fun onDetach() = Tutorial.forget(id, this)
}

private data class GuardElement(val any: Boolean) : ModifierNodeElement<GuardNode>() {
    override fun create() = GuardNode(any)

    override fun update(node: GuardNode) {
        if (node.any == any) return
        node.any = any
        node.refresh()
    }
}

private class GuardNode(var any: Boolean) : Modifier.Node(), SemanticsModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode {
    private var rect: Area? = null
    private var hidden = false

    override val shouldClearDescendantSemantics: Boolean get() = hidden

    override fun SemanticsPropertyReceiver.applySemantics() {}

    override fun onAttach() = refresh()

    override fun onObservedReadsChanged() = refresh()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val b = coordinates.boundsInWindow()
        rect = Area(b.left, b.top, b.right, b.bottom)
        refresh()
    }

    fun refresh() {
        var now = false
        observeReads {
            val shut = Tutorial.shut
            val at = rect
            now = shut != null && at != null && GateMath.shut(at, shut, any)
        }
        if (now != hidden) {
            hidden = now
            invalidateSemantics()
        }
    }
}
