package app.absentmotion.media

import app.absentmotion.model.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewVideoStampTest {
    private val scene = Scene.parse("<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"1000\"/>")

    @Test fun anOldScrubFrameCannotAcknowledgeANewRequestedTime() {
        val stamp = PreviewVideoStamp(scene, 100, false)
        assertTrue(stamp.matches(scene, 100, false))
        assertFalse(stamp.matches(scene, 133, false))
        assertFalse(stamp.matches(Scene(scene.node.with("width", "200")), 100, false))
    }

    @Test fun playbackMaySkipDecodedFramesButASeekMustSettleExactly() {
        val stamp = PreviewVideoStamp(scene, 100, true)
        assertTrue(stamp.matches(scene, 133, true))
        assertFalse(stamp.matches(scene, 133, false))
        assertTrue(stamp.matches(scene, 100, false))
        assertFalse(PreviewVideoStamp(scene, 100, false).matches(scene, 133, true))
    }
}
