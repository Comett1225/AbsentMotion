package app.absentmotion.pc.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.prefs.Preferences

class LibrarySettings(private val prefs: Preferences = Preferences.userNodeForPackage(LibrarySettings::class.java)) {
    companion object { val shared by lazy { LibrarySettings() } }

    var favorites by mutableStateOf(prefs.get("favorites", "").split('\n').filter { it.isNotBlank() }.toSet())
        private set

    fun contains(effect: String, preset: String? = null) = key(effect, preset) in favorites
    fun toggle(effect: String, preset: String? = null) {
        val key = key(effect, preset)
        favorites = if (key in favorites) favorites - key else favorites + key
        prefs.put("favorites", favorites.sorted().joinToString("\n"))
    }
    private fun key(effect: String, preset: String?) = if (preset == null) effect else "$effect/${preset}"
}
