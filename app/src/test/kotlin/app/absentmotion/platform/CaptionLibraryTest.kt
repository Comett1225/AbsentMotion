package app.absentmotion.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLibraryTest {
    private val a55 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val a53 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"

    @Test fun fastLibraryNeedsDotProductAndHalfFloatsOnEveryCore() {
        assertTrue(fastCaptionCpu("processor\t: 0\n$a55\n\nprocessor\t: 1\n$a55\n"))
        assertFalse(fastCaptionCpu("processor\t: 0\n$a55\n\nprocessor\t: 1\n$a53\n"))
        assertFalse(fastCaptionCpu(a53))
        assertFalse(fastCaptionCpu(""))
    }

    @Test fun fastLibraryIsTriedFirstAndBaselineIsTheFallback() {
        val tried = mutableListOf<String>()
        assertEquals("whisper_jni_fast", loadCaptionLibrary(true) { tried += it })
        assertEquals("whisper_jni", loadCaptionLibrary(false) { tried += it })
        assertEquals("whisper_jni", loadCaptionLibrary(true) { tried += it; if (it.endsWith("fast")) throw UnsatisfiedLinkError(it) })
        assertEquals(listOf("whisper_jni_fast", "whisper_jni", "whisper_jni_fast", "whisper_jni"), tried)
    }
}
