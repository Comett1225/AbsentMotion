package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetFavouritesTest {
    private val preset = EffectPreset("Soft Glow", emptyMap(), emptyMap())
    private val effect = EffectDefinition("absent.glow", "glow", "Glow", "color", "", emptyList(), presets = listOf(preset))

    @Test fun toggleStoresKeyAndRestoresIt() {
        var saved = emptySet<String>()
        val favourites = PresetFavourites(saved) { saved = it }
        assertFalse(favourites.contains(effect, preset))
        favourites.toggle(effect, preset)
        assertEquals(setOf("absent.glow#soft-glow"), saved)
        assertTrue(favourites.contains(effect, preset))
        val restored = PresetFavourites(saved) { saved = it }
        assertEquals(listOf(effect to preset), restored.resolve(listOf(effect)))
        restored.toggle(effect, preset)
        assertTrue(saved.isEmpty())
        assertFalse(restored.contains(effect, preset))
    }

    @Test fun unknownEntriesAreIgnored() {
        val favourites = PresetFavourites(setOf("absent.glow#missing", "absent.missing#soft-glow", "invalid", "absent.glow#soft-glow")) { }
        assertEquals(listOf(effect to preset), favourites.resolve(listOf(effect)))
        assertTrue(favourites.resolve(emptyList()).isEmpty())
    }
}
