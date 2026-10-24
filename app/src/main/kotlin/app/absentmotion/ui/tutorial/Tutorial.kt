package app.absentmotion.ui.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import app.absentmotion.library.AppSettings

object Tutorial {
    const val ASK = 1
    const val NEVER = 2

    internal var tour by mutableStateOf<Tour?>(null)
    internal var request by mutableStateOf<Boolean?>(null)
    internal var offerDismissed by mutableStateOf(false)
    internal var listener: ((Boolean) -> Unit)? = null
    internal val anchors = mutableStateMapOf<String, Rect>()
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
    }
}

fun Modifier.tutorialTarget(id: String?): Modifier = if (id == null || Tutorial.tour == null) this else this then TargetElement(id)

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
