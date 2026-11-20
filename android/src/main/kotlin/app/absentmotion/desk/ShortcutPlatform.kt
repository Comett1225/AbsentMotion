package app.absentmotion.desk

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.nativeKeyCode

object ShortcutPlatform {
    @Volatile var prefs: android.content.SharedPreferences? = null
    const val mac = false
    fun isCommand(event: KeyEvent): Boolean = event.isCtrlPressed || event.isMetaPressed
    fun bindable(event: KeyEvent): Boolean = true
    fun keyName(key: Key): String = when (key) {
        Key.MoveHome -> "Home"
        Key.MoveEnd -> "End"
        Key.Delete -> "Delete"
        Key.Backspace -> "Backspace"
        Key.Spacebar -> "Space"
        Key.Escape -> "Esc"
        else -> android.view.KeyEvent.keyCodeToString(key.nativeKeyCode).removePrefix("KEYCODE_").split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }
}
