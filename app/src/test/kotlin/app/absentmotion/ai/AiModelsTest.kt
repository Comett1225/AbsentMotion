package app.absentmotion.ai

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.ui.onboarding.PhoneSpeed
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue((AiModel.entries - rife.toSet() - AiModel.Cutout).all { m -> m.files.all { it.url == MODEL_RELEASE + it.name } })
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

    @Test fun quickSharpenFindsTheSharpenEffectInTheCatalog() {
        val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }
        val effect = catalog.find(QUICK_SHARPEN)
        assertNotNull(effect)
        assertTrue(effect!!.available)
        assertEquals("sharpen", effect.key)
        assertNull(catalog.find("sharpen"))
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
        assertNull(first.diedBefore())
        first.begin()
        assertEquals("first", stored)
        assertNull(first.diedBefore())
        first.begin()
        first.end()
        assertEquals("first", stored)
        first.end()
        assertNull(stored)
        first.end()
        assertEquals(listOf("first", null), writes)
        first.begin()
        val next = GpuMark("next", { stored }) { stored = it }
        assertEquals("first", next.diedBefore())
        stored = null
        assertNull(next.diedBefore())
        next.begin()
        assertNull(next.diedBefore())
        assertEquals("next", GpuMark("later", { stored }) { stored = it }.diedBefore())
    }

    @Test fun aForgivenDeathLeavesNoMarkButNeverErasesTheRunningOne() {
        var stored: String? = "1234:old"
        val mark = GpuMark("1235:new", { stored }) { stored = it }
        assertEquals("1234:old", mark.diedBefore())
        mark.forgive()
        assertNull(stored)
        mark.begin()
        mark.forgive()
        assertEquals("1235:new", stored)
    }

    @Test fun aJobLastSeenInTheBackgroundLeavesAnAwayMark() {
        var stored: String? = null
        val first = GpuMark("1:a", { stored }) { stored = it }
        first.seen(false)
        assertNull(stored)
        first.begin()
        assertEquals("1:a~", stored)
        first.seen(true)
        assertEquals("1:a", stored)
        first.seen(false)
        first.seen(false)
        assertEquals("1:a~", stored)
        val next = GpuMark("2:b", { stored }) { stored = it }
        val record = next.diedBefore()!!
        assertEquals("1:a~", record)
        assertTrue(gpuRecordAway(record))
        assertEquals("1:a", gpuRecordToken(record))
        assertFalse(gpuRecordAway("1:a"))
        assertEquals("1:a", gpuRecordToken("1:a"))
        first.end()
        assertNull(stored)
    }

    @Test fun theRunningProcessNeverTakesItsOwnMarkForADeath() {
        var stored: String? = null
        val mark = GpuMark("7:x", { stored }) { stored = it }
        mark.begin()
        mark.seen(false)
        assertNull(mark.diedBefore())
        mark.forgive()
        assertEquals("7:x~", stored)
    }

    @Test fun aJavaCrashLeavesNoMarkForTheGpuToBeBlamed() {
        var stored: String? = null
        val mark = GpuMark("7:x", { stored }) { stored = it }
        mark.begin()
        mark.crashed()
        assertNull(stored)
        assertNull(GpuMark("8:y", { stored }) { stored = it }.diedBefore())
        mark.end()
        mark.begin()
        assertEquals("7:x", stored)
        stored = "6:old"
        GpuMark("9:z", { stored }) { stored = it }.crashed()
        assertEquals("6:old", stored)
    }

    @Test fun aDeathWithoutAReasonBlamesTheGpuOnlyWhenTheAppWasOnScreen() {
        assertTrue(gpuDeathBlamed(null, true))
        assertFalse(gpuDeathBlamed(null, false))
        assertTrue(gpuDeathBlamed(ApplicationExitInfo.REASON_CRASH_NATIVE, false))
        assertTrue(gpuDeathBlamed(ApplicationExitInfo.REASON_ANR, false))
        assertFalse(gpuDeathBlamed(ApplicationExitInfo.REASON_LOW_MEMORY, true))
    }

    @Test fun onlyADeathThatLooksLikeACrashBlamesTheGpu() {
        assertTrue(gpuDeathBlamed(null))
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)) {
            assertTrue("reason $reason", gpuDeathBlamed(reason))
        }
        for (reason in listOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)) {
            assertFalse("reason $reason", gpuDeathBlamed(reason))
        }
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
        assertEquals(22.2 * 300 * 960 * 540 / 1e6, AiSmooth.gmac(SmoothLevel.Medium, 300.0, 960, 540), 1e-6)
        assertEquals(22.2 * 300 * flowPixels(SmoothLevel.Medium, Long.MAX_VALUE) / 1e6, AiSmooth.gmac(SmoothLevel.Medium, 300.0, 3840, 2160), 1e-6)
        assertEquals(1_166_666, flowPixels(SmoothLevel.Medium, 12_000_000_000L))
        assertEquals(555_555, flowPixels(SmoothLevel.Medium, 4_000_000_000L))
        assertEquals(227_272, flowPixels(SmoothLevel.High, 0L))
    }

    @Test fun lumaUsesStudioRange() {
        assertEquals(16, lumaOf(0xFF000000.toInt()))
        assertEquals(235, lumaOf(0xFFFFFFFF.toInt()))
    }

    @Test fun aHangingGpuCheckIsCancelledAndCountsAsNoAnswer() = kotlinx.coroutines.runBlocking {
        val hanging = kotlinx.coroutines.CompletableDeferred<Boolean>()
        assertNull(awaitProbe(hanging, 50))
        assertTrue(hanging.isCancelled)
        assertEquals(true, awaitProbe(kotlinx.coroutines.CompletableDeferred(true), 50))
    }
}
