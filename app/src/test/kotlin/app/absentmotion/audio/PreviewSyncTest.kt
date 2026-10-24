package app.absentmotion.audio

import app.absentmotion.awaitCondition
import app.absentmotion.sceneXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PreviewSyncTest {
    private val rate = 48_000
    private val block = 1024
    private val dt = 1000.0 / rate
    private val fade = 128

    private fun check(layer: String, vararg starts: Double, within: Float = 2e-3f) {
        val clip = AudioClip.of(sceneXml(layer)).single()
        val export = OfflineMix({ ToneSource(440.0) }, listOf(clip), Resampler.ULTRA, { _, _ -> null }, FakeFx(), rate).use { it.floats(0, 9_000_000) }
        val stream = LayerStream("tone", clip.mediaAt(starts.first()) - LayerStream.LEAD_MS) { ToneSource(440.0) }
        val rack = FxRack(rate) { _, _ -> null }
        val out = FloatArray(2 * block)
        try {
            for (start in starts) {
                var peak = 0f
                val first = Math.round(start / dt)
                for (b in 0 until 12) {
                    val frame = first + b * block
                    val t0 = frame * dt
                    awaitCondition("sound at ${t0.toInt()} ms") { stream.prepare(clip.wrap(clip.mediaAt(t0))) }
                    mixStreams(listOf(clip), out, block, t0, rate, Resampler.ULTRA, rack) { stream }
                    for (k in (if (b == 0) 2 * fade else 0) until 2 * block) {
                        assertEquals("$layer from $start ms, block $b", export[(2 * frame + k).toInt()], out[k], within)
                        peak = maxOf(peak, abs(out[k]))
                    }
                }
                assertTrue("$layer is silent from $start ms", peak > 0.4f)
            }
        } finally {
            stream.close()
        }
    }

    @Test fun playingFromTheMiddleAndAfterSeeksGivesTheExportsSampleAtEveryProjectTime() =
        check("""<audio id="1" src="tone" startTime="500" endTime="8499" inTime="1200"/>""", 3000.0, 6123.4, 1000.0, 7400.0)

    @Test fun aFasterClipStaysInSyncAndAudible() =
        check("""<audio id="1" src="tone" startTime="0" endTime="7999" speed="2"/>""", 3000.0, 500.0, 6000.0)

    @Test fun aSlowerClipStaysInSyncAndAudible() =
        check("""<audio id="1" src="tone" startTime="0" endTime="7999" speed="0.5"/>""", 3000.0, 7000.0, 100.0)

    @Test fun aSpeedRampStaysInSyncAndAudible() =
        check("""<audio id="1" src="tone" startTime="0" endTime="7999"><speedMap><kf t="0" v="0.5"/><kf t="9000000000" v="2"/></speedMap></audio>""", 2500.0, 6500.0, 1000.0, within = 2e-2f)

    @Test fun soundInsideAFasterGroupStaysInSyncAndAudible() =
        check("""<embedScene id="9" startTime="1000" endTime="6999" speed="2"><scene fps="30" totalTime="11999" retime="off"><audio id="1" src="tone" startTime="0" endTime="11999"/></scene></embedScene>""", 3000.0, 5500.0, 1500.0)
}
