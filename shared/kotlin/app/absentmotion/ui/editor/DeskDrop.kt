package app.absentmotion.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key

class DropItem(val label: String, val add: () -> Unit)

object DeskDrop {
    var item by mutableStateOf<DropItem?>(null)
    var at by mutableStateOf(Offset.Unspecified)
    var removeHovered: (() -> Unit)? = null
    var browserKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
    var panelKey: ((Boolean) -> Unit)? = null
    val keyNames = androidx.compose.runtime.mutableStateMapOf<String, String>()
    var panelChord: (androidx.compose.ui.input.key.KeyEvent) -> Boolean? = { event ->
        val key = event.key
        if ((event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed && (key == Key.One || key == Key.Two)) key == Key.One else null
    }
    var frameAt: (Offset) -> Int? = { null }

    fun clear() {
        item = null
        DeskGhost.picture = null
        at = Offset.Unspecified
    }
}
