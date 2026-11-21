package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectRecommendationsTest {
    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val photo = EffectRecommendationTarget(EffectTargetKind.Photo)
    private val text = EffectRecommendationTarget(EffectTargetKind.Text)

    private fun rank(
        vararg targets: EffectRecommendationTarget,
        recent: List<String> = emptyList(),
        favourites: Set<String> = emptySet(),
        definitions: List<EffectDefinition> = catalog.definitions,
    ) = recommendedEffects(definitions, targets.toList(), recent, favourites).map { it.key }

    @Test fun threeDifferentSuggestionsFitTheLayerWithNoHistory() {
        assertEquals(listOf("exposure", "sharpen", "vignette"), rank(photo))
        assertEquals(listOf("glow", "texttransform", "pulseopacity"), rank(text))
        assertEquals(listOf("directionalblur", "exposure", "sharpen"), rank(EffectRecommendationTarget(EffectTargetKind.Video, animated = true)))
        assertEquals(rank(photo), rank(photo, definitions = catalog.definitions.reversed()))
    }

    @Test fun recentAndFavouriteEffectsGiveContextualSuggestionsASmallBoost() {
        assertEquals("huesaturation", rank(photo, recent = listOf("absent.huesaturation")).first())
        assertEquals("brightnesscontrast", rank(photo, favourites = setOf("absent.brightnesscontrast")).first())
        assertEquals("glow", rank(text, recent = listOf("absent.exposure"), favourites = setOf("absent.exposure")).first())
        assertEquals("sharpen", rank(photo.copy(effects = setOf("absent.exposure"))).first())
    }

    @Test fun theRowSkipsAppliedHiddenAndUnavailableEffects() {
        val definitions = catalog.definitions.map {
            when (it.key) {
                "exposure" -> it.copy(status = "unsupported")
                "sharpen" -> it.copy(shortcut = "alias")
                else -> it
            }
        }
        val results = rank(photo.copy(effects = setOf("absent.vignette"), hiddenEffects = setOf("absent.huesaturation")), definitions = definitions)
        assertEquals(3, results.size)
        assertTrue(results.none { it in setOf("exposure", "sharpen", "vignette", "huesaturation") })
    }

    @Test fun mixedLayersSkipTextOnlyEffectsAndInactiveMotionDoesNotCount() {
        assertTrue(rank(photo, text).none { it.startsWith("text") })
        val video = EffectRecommendationTarget(EffectTargetKind.Video, effects = setOf("absent.oscillate"))
        assertEquals("directionalblur", rank(video).first())
        assertTrue("directionalblur" !in rank(video.copy(effects = emptySet(), hiddenEffects = video.effects)))
    }

    @Test fun aGreenScreenClipPutsChromaKeyFirst() {
        val green = MediaLook(screen = ScreenColor.Green)
        assertEquals("chromakey", rank(EffectRecommendationTarget(EffectTargetKind.Video, look = green)).first())
        assertEquals("chromakey", rank(photo.copy(look = MediaLook(screen = ScreenColor.Blue))).first())
        assertTrue("chromakey" !in rank(photo))
        assertTrue("chromakey" !in rank(photo.copy(look = green, effects = setOf("absent.chromakey"))))
    }

    @Test fun beatMarksShortClipsCutoutsAndDarkFootageGetTheEffectsTheyCallFor() {
        assertEquals("beatshake", rank(photo.copy(beats = true)).first())
        assertEquals(1, rank(photo.copy(beats = true)).count { it.startsWith("beat") })
        assertEquals("textshake", rank(text.copy(short = true)).first())
        assertEquals("autoshake", rank(EffectRecommendationTarget(EffectTargetKind.Shape, short = true)).first())
        assertTrue(rank(photo).none { it.startsWith("beat") || it.endsWith("shake") })
        assertEquals("dropshadow", rank(photo.copy(look = MediaLook(cutout = true))).first())
        assertEquals("exposure", rank(photo.copy(look = MediaLook(dark = true)), favourites = setOf("absent.huesaturation")).first())
    }

    @Test fun pixelsRevealGreenScreensCutoutsAndDarkFootageButNotLandscapes() {
        fun picture(pixel: (Int, Int) -> Int) = IntArray(40 * 30) { pixel(it % 40, it / 40) }
        val person = 0xffc89070.toInt()
        val screen = picture { x, y -> if (x in 14..25 && y in 6..29) person else 0xff00b140.toInt() }
        assertEquals(ScreenColor.Green, mediaLook(screen, 40, 30).screen)
        assertEquals(ScreenColor.Blue, mediaLook(picture { x, y -> if (x in 14..25 && y in 6..29) person else 0xff0047bb.toInt() }, 40, 30).screen)
        val landscape = picture { _, y -> if (y < 15) 0xff2a5ac8.toInt() else 0xff3c9a28.toInt() }
        assertEquals(null, mediaLook(landscape, 40, 30).screen)
        assertEquals(null, mediaLook(picture { _, _ -> 0xff648c3c.toInt() }, 40, 30).screen)
        assertTrue(mediaLook(picture { x, _ -> if (x < 10) 0 else person }, 40, 30).cutout)
        assertTrue(mediaLook(picture { _, _ -> 0xff1a1612.toInt() }, 40, 30).dark)
        assertTrue(!mediaLook(screen, 40, 30).dark && !mediaLook(screen, 40, 30).cutout)
        assertEquals(ScreenColor.Green, screenFromName("file:///clips/Dancer%20Green%20Screen%20(1).mp4"))
        assertEquals(null, screenFromName("file:///clips/greenery.jpg"))
    }

    @Test fun sparseCatalogsAndEmptySelectionsNeverInventResults() {
        assertTrue(rank().isEmpty())
        assertTrue(rank(photo, definitions = emptyList()).isEmpty())
        assertEquals(listOf("exposure"), rank(photo, definitions = listOf(catalog.find("absent.exposure")!!)))
    }
}
