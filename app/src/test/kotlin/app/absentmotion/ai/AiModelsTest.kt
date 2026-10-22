package app.absentmotion.ai

import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.ui.onboarding.PhoneSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AiModelsTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun everyModelFileIsPinnedWithSizeAndHash() {
        assertEquals(AiModel.entries.size, AiModel.entries.map { it.folder }.toSet().size)
        for (model in AiModel.entries) {
            assertTrue(model.files.isNotEmpty())
            assertTrue(model.licence.isNotBlank())
            assertTrue(model.gmacPerMegapixel > 0)
            for (file in model.files) {
                assertTrue(file.bytes > 0)
                assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(file.url.startsWith("https://"))
                assertTrue(file.url.endsWith(file.name))
            }
        }
        val rife = listOf(AiModel.SmoothLow, AiModel.SmoothMedium, AiModel.SmoothHigh)
        assertTrue(rife.all { m -> m.files.all { "/13338e38debe2e400b3eeecf6792312d01a692f9/models/${m.folder}/" in it.url } })
        assertTrue((AiModel.entries - rife.toSet()).all { m -> m.files.all { it.url == MODEL_RELEASE + it.name } })
        assertTrue(MODEL_RELEASE.endsWith("/releases/download/ai-models-1/"))
        assertNull(AiModel.Grain.param)
        assertEquals("realesr-general-x4v3.param", AiModel.Detail.param)
        assertEquals(11_276_252L + 35_830L, AiModel.SmoothLow.bytes)
    }

    @Test fun aStoppedDownloadResumesAndTheJoinedFileMustMatchTheHash() {
        val dir = java.nio.file.Files.createTempDirectory("ai-model").toFile()
        try {
            val good = ByteArray(200_000) { (it * 31 % 251).toByte() }
            val hash = sha(good)
            val target = File(dir, "model.bin")
            val part = File(dir, "model.bin.part")
            part.writeBytes(good.copyOf(70_000))
            var last = 0f
            CaptionModel.receive(good.copyOfRange(70_000, good.size).inputStream(), part, target, good.size.toLong(), hash, {}, { last = it }, append = true)
            assertTrue(target.readBytes().contentEquals(good))
            assertEquals(1f, last)
            assertFalse(part.exists())
            target.delete()
            part.writeBytes(good.copyOf(70_000))
            val forged = good.copyOfRange(70_000, good.size).also { it[5] = (it[5] + 1).toByte() }
            assertTrue(runCatching { CaptionModel.receive(forged.inputStream(), part, target, good.size.toLong(), hash, {}, {}, append = true) }.isFailure)
            assertFalse(target.exists())
            assertFalse("a broken download is not resumed", part.exists())
            part.writeBytes(good.copyOf(70_000))
            assertTrue(runCatching { CaptionModel.receive(good.inputStream(), part, target, good.size.toLong(), hash, {}, {}, append = true) }.isFailure)
            assertFalse("too many bytes are thrown away", part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun enhanceOptionsPickTheirModelsAndRoundTrip() {
        val plain = EnhanceOptions(EnhanceMode.Upscale2)
        assertEquals(AiModel.Detail, plain.model)
        assertNull(plain.blend)
        assertEquals(listOf(AiModel.Detail, AiModel.Grain), plain.copy(grain = true).models)
        assertEquals(listOf(AiModel.Cartoon), plain.copy(cartoon = true, grain = true).models)
        assertFalse(EnhanceOptions(EnhanceMode.Sharpen, quick = true).usesAi)
        assertTrue(EnhanceOptions(EnhanceMode.Upscale4, quick = true).usesAi)
        for (mode in EnhanceMode.entries) for (cartoon in listOf(false, true)) for (grain in listOf(false, true)) {
            val options = EnhanceOptions(mode, cartoon, grain && !cartoon)
            assertEquals(options, EnhanceOptions.parse(options.xml))
        }
        assertNull(EnhanceOptions.parse("blur"))
        assertNull(EnhanceOptions.parse(null))
        assertEquals("upscale4+cartoon", EnhanceOptions(EnhanceMode.Upscale4, cartoon = true).xml)
    }

    @Test fun inputCapsFollowTheShortSide() {
        assertTrue(EnhanceMode.Upscale2.fits(1280, 720))
        assertTrue(EnhanceMode.Upscale2.fits(720, 1280))
        assertFalse(EnhanceMode.Upscale2.fits(1920, 1080))
        assertTrue(EnhanceMode.Upscale4.fits(640, 360))
        assertFalse(EnhanceMode.Upscale4.fits(854, 480))
        assertTrue(EnhanceMode.Sharpen.fits(1920, 1080))
        assertFalse(EnhanceMode.Sharpen.fits(3840, 2160))
        assertTrue(EnhanceMode.Faces.fits(3840, 2160))
        assertFalse(EnhanceMode.Upscale2.fits(0, 0))
        assertEquals(listOf(2, 4, 1, 1), EnhanceMode.entries.map { it.scale })
    }

    @Test fun estimatesTurnIntoPlainVerdicts() {
        val fast = AiPhone(true, AiGpu(0x403000, 1, true, "gpu"), 8_000_000_000L, PhoneSpeed.Fast)
        val software = AiPhone(true, AiGpu(0x403000, 3, false, "cpu"), 8_000_000_000L, PhoneSpeed.Fast)
        val none = AiPhone(false, null, 8_000_000_000L, PhoneSpeed.Fast)
        val small = AiPhone(true, AiGpu(0x401000, 1, true, "gpu"), 3_000_000_000L, PhoneSpeed.Good)
        assertEquals(AiVerdict.Fine, aiEstimate(fast, 400.0 * 120, null).verdict)
        assertEquals(120.0, aiEstimate(fast, 400.0 * 120, null).seconds, 1e-6)
        assertEquals(AiVerdict.Struggle, aiEstimate(software, 1.0, null).verdict)
        assertEquals(AiVerdict.Cannot, aiEstimate(none, 1.0, null).verdict)
        assertEquals(AiVerdict.Struggle, aiEstimate(small, 1.0, null).verdict)
        assertEquals(AiVerdict.Struggle, aiEstimate(fast, 400.0 * 3600, null).verdict)
        assertEquals(AiVerdict.Cannot, aiEstimate(fast, 400.0 * 3600 * 9, null).verdict)
        assertEquals(10.0, aiEstimate(fast, 100.0, 10.0).seconds, 1e-6)
        assertTrue(aiEstimate(fast, 100.0, 10.0).measured)
        assertEquals("1.3", AiGpu(0x403000, 1, true, "x").version)
        assertEquals("1.1", AiGpu.parse("4198400\t1\t1\t0\tAdreno (TM) 740")!!.version)
        assertEquals("Adreno (TM) 740", AiGpu.parse("4198400\t1\t1\t0\tAdreno (TM) 740")!!.name)
        assertNull(AiGpu.parse("broken"))
        assertNull(AiGpu.parse(null))
    }

    @Test fun faceBoxesAreSmoothedAndDroppedAfterMisses() {
        val tracks = mutableListOf<FaceBox>()
        trackFaces(tracks, listOf(FaceBox(100f, 100f, 50f)))
        assertEquals(1, tracks.size)
        trackFaces(tracks, listOf(FaceBox(110f, 100f, 50f)))
        assertEquals(104f, tracks.single().cx, 1e-4f)
        trackFaces(tracks, listOf(FaceBox(400f, 400f, 60f)))
        assertEquals(2, tracks.size)
        repeat(7) { trackFaces(tracks, listOf(FaceBox(400f, 400f, 60f))) }
        assertEquals(2, tracks.size)
        trackFaces(tracks, listOf(FaceBox(400f, 400f, 60f)))
        assertEquals(1, tracks.size)
        assertEquals(400f, tracks.single().cx, 1e-3f)
    }

    @Test fun faceBlendIsSoftAtTheEdgesAndFullInTheMiddle() {
        assertEquals(1f, featherWeight(50, 50, 101, 101), 1e-6f)
        assertEquals(0f, featherWeight(0, 0, 101, 101), 1e-6f)
        assertEquals(0f, featherWeight(0, 50, 101, 101), 0.05f)
        val mid = featherWeight(8, 50, 101, 101)
        assertTrue(mid > 0f && mid < 1f)
        assertEquals(0xFF102030.toInt(), mixArgb(0xFF102030.toInt(), 0xFFF0E0D0.toInt(), 0f))
        assertEquals(0xFFF0E0D0.toInt(), mixArgb(0xFF102030.toInt(), 0xFFF0E0D0.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), mixArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test fun aiSmoothIsTheFourthMethodAndMatchesTheEngineModes() {
        assertEquals(listOf("Off", "Blend", "Flow", "Ai"), FrameInterpolation.entries.map { it.name })
        val chosen = Smoothing(FrameInterpolation.Ai, SmoothLevel.High)
        assertEquals(chosen, smoothingOf(chosen.option))
        assertEquals(listOf(AiModel.SmoothLow, AiModel.SmoothMedium, AiModel.SmoothHigh), SmoothLevel.entries.map { it.model })
        assertEquals(listOf(128, 64, 64), SmoothLevel.entries.map { it.padding })
        assertEquals(22.2 * 300 * 1920 * 1080 / 1e6, AiSmooth.gmac(SmoothLevel.Medium, 300.0, 1920, 1080), 1e-6)
    }

    @Test fun lumaUsesStudioRange() {
        assertEquals(16, lumaOf(0xFF000000.toInt()))
        assertEquals(235, lumaOf(0xFFFFFFFF.toInt()))
    }
}
