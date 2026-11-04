package app.absentmotion.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef

@Composable
internal fun linkedNumbers(state: EditorState, layer: Layer, refs: List<PropRef>): Map<PropRef, FloatArray> {
    val linked = refs.filter { ref -> layer.element(ref)?.children?.any { it.name == "valueLink" } == true }
    val values by produceState(emptyMap<PropRef, FloatArray>(), state.scene.node, layer.id, state.sampleMs, linked) {
        value = linked.mapNotNull { ref ->
            state.engine.propertyNumbers(layer.id, ref.effectIndex, ref.name, state.sampleMs)?.let { ref to it }
        }.toMap()
    }
    return values.filterKeys { it in linked }
}
