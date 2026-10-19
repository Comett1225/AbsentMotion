package app.absentmotion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackgroundRemovalTest {
    @Test fun smallPicturesKeepTheEngineLimit() {
        assertEquals(4096, matteLimit(1920, 1080, 4096))
        assertEquals(1000, matteLimit(4000, 3000, 1000))
        assertEquals(4096, matteLimit(4096, 4096, 8192, 4096L * 4096))
    }

    @Test fun hugePicturesAreScaledToThePixelBudget() {
        for ((w, h) in listOf(16320 to 12240, 12000 to 9000, 30000 to 1000, 9000 to 9000, 8000 to 6000)) {
            val side = matteLimit(w, h, 100_000)
            val scale = side.toDouble() / maxOf(w, h)
            val area = (w * scale).toLong() * (h * scale).toLong()
            assertTrue("$w x $h -> $side", area <= MAX_MATTE_PIXELS)
            assertTrue("$w x $h -> $side", area > MAX_MATTE_PIXELS * 0.99)
        }
        assertEquals(1, matteLimit(1, 1, 0))
    }

    @Test fun theModelIsCopiedOnceAndAgainAfterAnUpdate() {
        val dir = Files.createTempDirectory("bgmodel").toFile()
        try {
            val target = File(dir, MODEL_ASSET)
            val stamp = File(dir, "$MODEL_ASSET.stamp")
            assertTrue(needsCopy(target, stamp, "100"))
            target.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(needsCopy(target, stamp, "100"))
            stamp.writeText("100")
            assertFalse(needsCopy(target, stamp, "100"))
            assertTrue(needsCopy(target, stamp, "200"))
            target.delete()
            assertTrue(needsCopy(target, stamp, "100"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theMatteScalesAlphaAndKeepsColour() {
        assertEquals(0x00123456, withMatte(0xFF123456.toInt(), 0))
        assertEquals(0xFF123456.toInt(), withMatte(0xFF123456.toInt(), 255.toByte()))
        assertEquals(0x80123456.toInt(), withMatte(0xFF123456.toInt(), 128.toByte()))
        assertEquals(0x40ABCDEF, withMatte(0x80ABCDEF.toInt(), 128.toByte()))
        assertEquals(0x00ABCDEF, withMatte(0x00ABCDEF, 255.toByte()))
        for (a in 0..255) for (m in 0..255) {
            assertEquals(Math.round(a * (m / 255f)), withMatte((a shl 24) or 0x445566, m.toByte()) ushr 24)
        }
    }
}
