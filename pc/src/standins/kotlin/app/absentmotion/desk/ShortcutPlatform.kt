package app.absentmotion.desk

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode

object ShortcutPlatform {
    val prefs: android.content.SharedPreferences? get() = android.content.SharedPreferences.named("desk")
    val mac: Boolean get() = DeskOs.mac
    fun isCommand(event: KeyEvent): Boolean = DeskKeys.isCommand(event)
    fun bindable(event: KeyEvent): Boolean = DeskKeys.bindable(event)
    fun keyName(key: Key): String = java.awt.event.KeyEvent.getKeyText(key.nativeKeyCode)
}
