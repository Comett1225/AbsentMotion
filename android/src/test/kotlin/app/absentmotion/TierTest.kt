package app.absentmotion

import app.absentmotion.export.freeFile
import app.absentmotion.platform.drain
import app.absentmotion.platform.readAtMost
import app.absentmotion.platform.readInto
import app.absentmotion.ui.home.lowMemory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class TierTest {
    @Test fun latestHasEverythingAndCompatibleLeavesOutWhatTheListSays() {
        for (feature in Feature.entries) {
            assertEquals(!BuildConfig.COMPATIBLE || feature.inCompatible, Tier.has(feature))
            assertEquals(Tier.has(feature), Tier.reason(feature) == null)
            val choice = Tier.choice(feature.name, feature)
            assertEquals(!Tier.has(feature), choice.locked)
            assertEquals(Tier.has(feature), choice.enabled)
        }
        assertEquals(Feature.entries.filter { BuildConfig.COMPATIBLE && !it.inCompatible }, Tier.leftOut)
        assertEquals(BuildConfig.COMPATIBLE, !Tier.has(Feature.FlowHigh))
    }

    @Test fun onlyPhonesUnderThreeGigabytesAreWarned() {
        assertTrue(lowMemory(1_900_000_000L))
        assertFalse(lowMemory(2_800_000_000L))
        assertFalse(lowMemory(3_700_000_000L))
        assertFalse(lowMemory(0L))
    }

    @Test fun streamHelpersReadLikeTheJavaElevenCalls() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertArrayEquals(bytes.copyOf(12_345), ByteArrayInputStream(bytes).readAtMost(12_345))
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(50_000))
        val trickle = object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (at >= bytes.size) -1 else { b[off] = bytes[at++]; 1 }
        }
        val target = ByteArray(100)
        assertEquals(100, trickle.readInto(target, 0, 100))
        assertArrayEquals(bytes.copyOf(100), target)
        trickle.drain()
        assertEquals(bytes.size, trickle.at)
    }

    @Test fun androidNineExportsNeverOverwriteAFile() {
        val dir = Files.createTempDirectory("free-file").toFile()
        try {
            assertEquals("clip.mp4", freeFile(dir, "clip.mp4").name)
            dir.resolve("clip.mp4").writeText("x")
            assertEquals("clip (2).mp4", freeFile(dir, "clip.mp4").name)
            dir.resolve("clip (2).mp4").writeText("x")
            assertEquals("clip (3).mp4", freeFile(dir, "clip.mp4").name)
            assertEquals("notes", freeFile(dir, "notes").name)
        } finally { dir.deleteRecursively() }
    }
}
