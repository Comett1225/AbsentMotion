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
        assertEquals(rife, AiModel.entries)
        assertTrue(rife.all { it.param == "flownet.param" && it.bin == "flownet.bin" })
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

    @Test fun estimatesTurnIntoPlainVerdicts() {
        val fast = AiPhone(true, AiGpu(0x403000, 1, true, "Adreno (TM) 750", true), 8_000_000_000L, PhoneSpeed.Fast)
        val software = AiPhone(true, AiGpu(0x403000, 3, false, "cpu"), 8_000_000_000L, PhoneSpeed.Fast)
        val none = AiPhone(false, null, 8_000_000_000L, PhoneSpeed.Fast)
        val small = AiPhone(true, AiGpu(0x401000, 1, true, "gpu"), 3_000_000_000L, PhoneSpeed.Good)
        val tablet = AiPhone(true, AiGpu(0x401000, 1, false, "Adreno (TM) 610"), 4_000_000_000L, PhoneSpeed.Basic)
        assertFalse(tablet.usesGpu)
        assertEquals(60.0, aiEstimate(tablet, 6.0 * 60, null).seconds, 1e-6)
        assertEquals(60.0, aiEstimate(AiPhone(true, fast.gpu, 8_000_000_000L, PhoneSpeed.Fast, usesGpu = false), 40.0 * 60, null).seconds, 1e-6)
        assertEquals(AiVerdict.Struggle, aiEstimate(AiPhone(true, fast.gpu, 8_000_000_000L, PhoneSpeed.Fast, usesGpu = false), 40.0 * 601, null).verdict)
        assertEquals(AiVerdict.Fine, aiEstimate(fast, 400.0 * 120, null).verdict)
        assertEquals(120.0, aiEstimate(fast, 400.0 * 120, null).seconds, 1e-6)
        assertEquals(AiVerdict.Fine, aiEstimate(software, 1.0, null).verdict)
        assertEquals(AiVerdict.Struggle, aiEstimate(software, 40.0 * 601, null).verdict)
        assertEquals(AiVerdict.Cannot, aiEstimate(none, 1.0, null).verdict)
        assertEquals(AiVerdict.Struggle, aiEstimate(small, 1.0, null).verdict)
        assertEquals(AiVerdict.Struggle, aiEstimate(fast, 400.0 * 3600, null).verdict)
        assertEquals(AiVerdict.Cannot, aiEstimate(fast, 400.0 * 3600 * 9, null).verdict)
        assertEquals(10.0, aiEstimate(fast, 100.0, 10.0).seconds, 1e-6)
        assertTrue(aiEstimate(fast, 100.0, 10.0).measured)
        assertEquals("1.3", AiGpu(0x403000, 1, true, "x").version)
        assertEquals("1.1", AiGpu.parse("4198400\t1\t1\t0\t1\tAdreno (TM) 740")!!.version)
        assertEquals("Adreno (TM) 740", AiGpu.parse("4198400\t1\t1\t0\t1\tAdreno (TM) 740")!!.name)
        assertTrue(AiGpu.parse("4206592\t1\t1\t1\t1\tAdreno (TM) 740")!!.pushDescriptor)
        assertNull(AiGpu.parse("4198400\t1\t1\t0\tAdreno (TM) 740"))
        assertNull(AiGpu.parse("broken"))
        assertNull(AiGpu.parse(null))
    }

    private fun gpu(name: String, api: Int = 0x403000, type: Int = 1, fp16: Boolean = true, push: Boolean = true) = AiGpu(api, type, fp16, name, push)

    @Test fun onlyKnownGraphicsChipsRunAiByDefault() {
        for (name in listOf("Adreno (TM) 840", "Adreno (TM) 830", "Adreno (TM) 750", "Adreno (TM) 740", "Adreno (TM) 710",
            "Mali-G710 MC10", "Mali-G715-Immortalis MC11", "Mali-G720-Immortalis MC12", "Mali-G925-Immortalis MC12", "Mali-G610 MC6", "Mali-G615 MC2")) {
            assertTrue(name, gpu(name).known)
            assertTrue(name, aiUsesGpu(gpu(name), null))
        }
        for (name in listOf("Adreno (TM) 610", "Adreno (TM) 618", "Adreno (TM) 660", "Adreno (TM) 690", "Adreno (TM) 512", "Adreno (TM) 6xx",
            "Turnip Adreno (TM) 740", "Mali-G57 MC2", "Mali-G78 MP20", "Mali-G77 MC9", "Mali-G52 MC2", "Mali-G72 MP3", "Mali-G310", "Mali-G510 MC2",
            "Mali-T880", "Mali-G1-Ultra MC12", "PowerVR B-Series BXM-8-256", "PowerVR D-Series DXT-48-1536", "PowerVR Rogue GE8320",
            "Samsung Xclipse 940", "Xclipse 950", "SwiftShader Device (Subzero)", "Android Emulator", "llvmpipe (LLVM 17.0.6, 128 bits)", "", "Adreno (TM) 7400")) {
            assertFalse(name, gpu(name).known)
            assertFalse(name, aiUsesGpu(gpu(name), null))
        }
        assertFalse(gpu("Adreno (TM) 840", api = 0x401000).known)
        assertFalse(gpu("Mali-G720-Immortalis MC12", api = 0x402000).known)
        assertTrue(gpu("Adreno (TM) 840", api = 0x404000).known)
        assertFalse(gpu("Adreno (TM) 750", fp16 = false).known)
        assertFalse(gpu("Mali-G715 MC7", push = false).known)
        assertFalse(gpu("Adreno (TM) 840", type = 3).known)
        assertFalse(aiUsesGpu(null, null))
        assertFalse(aiUsesGpu(null, true))
    }

    @Test fun theSwitchOverridesTheList() {
        assertTrue(aiUsesGpu(gpu("Adreno (TM) 610", api = 0x401000, fp16 = false), true))
        assertFalse(aiUsesGpu(gpu("Adreno (TM) 840"), false))
        assertFalse(aiUsesGpu(gpu("SwiftShader Device (Subzero)", type = 3), true))
        assertFalse(aiUsesGpu(gpu("Android Emulator", type = 3), null))
    }

    @Test fun aGpuJobLeftRunningMeansThePreviousRunDied() {
        var stored: String? = null
        val writes = mutableListOf<String?>()
        val first = GpuMark("first", { stored }) { stored = it; writes += it }
        assertFalse(first.diedBefore())
        first.begin()
        assertEquals("first", stored)
        assertFalse(first.diedBefore())
        first.begin()
        first.end()
        assertEquals("first", stored)
        first.end()
        assertNull(stored)
        first.end()
        assertEquals(listOf("first", null), writes)
        first.begin()
        val next = GpuMark("next", { stored }) { stored = it }
        assertTrue(next.diedBefore())
        stored = null
        assertFalse(next.diedBefore())
        next.begin()
        assertFalse(next.diedBefore())
        assertTrue(GpuMark("later", { stored }) { stored = it }.diedBefore())
    }

    @Test fun aiSmoothIsTheFourthMethodAndMatchesTheEngineModes() {
        assertEquals(listOf("Off", "Blend", "Flow", "Ai"), FrameInterpolation.entries.map { it.name })
        val chosen = Smoothing(FrameInterpolation.Ai, SmoothLevel.High)
        assertEquals(chosen, smoothingOf(chosen.option))
        assertEquals(listOf(AiModel.SmoothLow, AiModel.SmoothMedium, AiModel.SmoothHigh), SmoothLevel.entries.map { it.model })
        assertEquals(listOf(128, 64, 64), SmoothLevel.entries.map { it.padding })
        assertEquals(22.2 * 300 * 960 * 540 / 1e6, AiSmooth.gmac(SmoothLevel.Medium, 300.0, 960, 540), 1e-6)
        assertEquals(22.2 * 300 * flowPixels(SmoothLevel.Medium, Long.MAX_VALUE) / 1e6, AiSmooth.gmac(SmoothLevel.Medium, 300.0, 3840, 2160), 1e-6)
        assertEquals(1_166_666, flowPixels(SmoothLevel.Medium, 12_000_000_000L))
        assertEquals(555_555, flowPixels(SmoothLevel.Medium, 4_000_000_000L))
        assertEquals(227_272, flowPixels(SmoothLevel.High, 0L))
    }
}
