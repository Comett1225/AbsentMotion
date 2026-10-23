package app.absentmotion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class AiVideoCleanupTest {
    private class Encoder(private val log: MutableList<String>, private val fails: Boolean) : Closeable {
        override fun close() {
            log += "close"
            if (fails) throw IllegalStateException("muxer stop failed")
        }
    }

    @Test fun closesThenStopsThermalThenDiscards() {
        val log = mutableListOf<String>()
        releaseJob(Encoder(log, false), { log += "thermal" }) { log += "discard" }
        assertEquals(listOf("close", "thermal", "discard"), log)
    }

    @Test fun aFailingCloseStillStopsThermalAndDiscardsTheFile() {
        val log = mutableListOf<String>()
        val error = runCatching { releaseJob(Encoder(log, true), { log += "thermal" }) { log += "discard" } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(listOf("close", "thermal", "discard"), log)
    }

    @Test fun aFailingThermalStopStillDiscards() {
        val log = mutableListOf<String>()
        runCatching { releaseJob(null, { error("thermal") }) { log += "discard" } }
        assertEquals(listOf("discard"), log)
    }

    @Test fun noEncoderStillCleansUp() {
        val log = mutableListOf<String>()
        releaseJob(null, { log += "thermal" }) { log += "discard" }
        assertEquals(listOf("thermal", "discard"), log)
    }

    private fun forward(x: Float, y: Float, width: Float, height: Float, turn: Int) = when (turn) {
        90 -> (height - y) to x
        180 -> (width - x) to (height - y)
        270 -> y to (width - x)
        else -> x to y
    }

    @Test fun aFaceFoundInTheUprightPictureMapsBackToTheSidewaysFrame() {
        for (turn in listOf(0, 90, 180, 270)) for ((x, y) in listOf(10f to 20f, 500f to 40f, 639f to 359f, 320f to 180f)) {
            val (u, v) = forward(x, y, 640f, 360f, turn)
            val (backX, backY) = uprightToFrame(u, v, 640f, 360f, turn)
            assertEquals("turn $turn x", x, backX, 1e-3f)
            assertEquals("turn $turn y", y, backY, 1e-3f)
        }
    }

    @Test fun aPortraitPhoneClipFindsItsFaceNearTheLeftOfTheFrame() {
        val (x, y) = uprightToFrame(180f, 400f, 1920f, 1080f, 90)
        assertEquals(400f, x, 1e-3f)
        assertEquals(1080f - 180f, y, 1e-3f)
    }

    @Test fun displayTurnIsAMultipleOf90() {
        assertEquals(listOf(0, 90, 180, 270, 0, 270), listOf(0, 90, 180, 270, 360, -90).map(::displayTurn))
        assertEquals(90, displayTurn(95))
    }
}
