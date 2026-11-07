package app.absentmotion.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

class DropItem(val label: String, val add: () -> Unit)

object DeskDrop {
    var item by mutableStateOf<DropItem?>(null)
    var at by mutableStateOf(Offset.Unspecified)
    var removeHovered: (() -> Unit)? = null
    var browserKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
    var panelKey: ((Boolean) -> Unit)? = null
    var frameAt: (Offset) -> Int? = { null }

    fun clear() {
        item = null
        DeskGhost.picture = null
        at = Offset.Unspecified
    }
}
