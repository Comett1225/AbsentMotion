package app.absentmotion.audio

import app.absentmotion.sceneXml
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FxRackTest {
    private val clip = AudioClip.of(sceneXml(
        """<audio id="1" src="tone1" startTime="0" endTime="999" outTime="30000"><audioEffects><fx type="eq"/></audioEffects></audio>""",
    )).single()

    @Test fun aChainWhoseTailHasRunOutIsFreedAndMadeAgainWhenTheClipPlaysAgain() {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val make: FxMaker = { _, _ ->
            opened.incrementAndGet()
            object : FakeFx() { override fun close() { closed.incrementAndGet() } }
        }
        val rack = FxRack(1000, make)
        val block = FloatArray(200)
        fun block(rendered: Boolean) {
            rack.target(clip, block, 100)
            rack.finish(clip, block, 100, rendered)
            rack.sweep()
        }
        block(true)
        assertEquals(1, opened.get())
        repeat(25) { block(false) }
        assertEquals("the tail is still ringing", 0, closed.get())
        repeat(10) { block(false) }
        assertEquals(1, closed.get())
        block(true)
        assertEquals(2, opened.get())
        rack.close()
        assertEquals(2, closed.get())
    }

    @Test fun aClipThatNeverPlaysNeverMakesAChain() {
        val opened = AtomicInteger()
        val rack = FxRack(1000) { _, _ -> opened.incrementAndGet(); FakeFx() }
        val block = FloatArray(200)
        repeat(50) {
            rack.target(clip, block, 100)
            rack.finish(clip, block, 100, false)
            rack.sweep()
        }
        assertEquals(0, opened.get())
    }
}
