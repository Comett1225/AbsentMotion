package app.absentmotion

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.exifOrientation
import app.absentmotion.model.fpsText
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDeclarationTest {
    @Test fun aVideosFrameRateIsStoredAsFramesPerSecond() {
        assertEquals("30.00", fpsText(30f))
        assertEquals("29.97", fpsText(29.97f))
        assertEquals(30f, MediaDeclaration(XmlNode("media", mapOf("fps" to fpsText(30f)))).fps)
    }

    @Test fun everyExifOrientationGivesItsTurnAndMirror() {
        val expected = mapOf(0 to (0 to false), 1 to (0 to false), 2 to (0 to true), 3 to (180 to false), 4 to (180 to true),
            5 to (270 to true), 6 to (90 to false), 7 to (90 to true), 8 to (270 to false), 9 to (0 to false))
        for ((tag, turn) in expected) assertEquals("tag $tag", turn, exifOrientation(tag))
    }
}
