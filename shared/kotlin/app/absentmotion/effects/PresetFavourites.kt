package app.absentmotion.effects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PresetFavourites(initial: Set<String>, private val save: (Set<String>) -> Unit) {
    var keys by mutableStateOf(initial.toSet())
        private set

    fun contains(effect: EffectDefinition, preset: EffectPreset): Boolean = key(effect, preset) in keys

    fun toggle(effect: EffectDefinition, preset: EffectPreset) {
        val key = key(effect, preset)
        keys = if (key in keys) keys - key else keys + key
        save(keys)
    }

    fun resolve(definitions: List<EffectDefinition>): List<Pair<EffectDefinition, EffectPreset>> =
        definitions.flatMap { effect -> effect.presets.mapNotNull { preset -> if (contains(effect, preset)) effect to preset else null } }

    companion object {
        fun key(effect: EffectDefinition, preset: EffectPreset): String = "${effect.id}#${preset.slug}"
    }
}

internal object PresetFavouriteStore {
    @Volatile private var instance: PresetFavourites? = null

    fun get(context: Context): PresetFavourites = instance ?: synchronized(this) {
        instance ?: context.applicationContext.getSharedPreferences("effects", 0).let { prefs ->
            PresetFavourites(prefs.getStringSet("preset_favourites", emptySet()).orEmpty()) { keys ->
                prefs.edit().putStringSet("preset_favourites", keys).apply()
            }.also { instance = it }
        }
    }
}
