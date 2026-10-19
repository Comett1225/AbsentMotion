package app.absentmotion

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDeclarationTest {
    @Test fun aVideosFrameRateIsStoredAsFramesPerSecond() {
        assertEquals("30.00", fpsText(30f))
        assertEquals("29.97", fpsText(29.97f))
        assertEquals(30f, MediaDeclaration(XmlNode("media", mapOf("fps" to fpsText(30f)))).fps)
    }
}
