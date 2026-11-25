package app.absentmotion.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionLibraryTest {
    private val a55 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val a53 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"
    private val x925 = "$a55 sve i8mm bf16"

    @Test fun libraryLevelNeedsTheFeaturesOnEveryCore() {
        assertEquals(1, captionCpuLevel("processor\t: 0\n$a55\n\nprocessor\t: 1\n$a55\n"))
        assertEquals(0, captionCpuLevel("processor\t: 0\n$a55\n\nprocessor\t: 1\n$a53\n"))
        assertEquals(2, captionCpuLevel("processor\t: 0\n$x925\n\nprocessor\t: 1\n$x925\n"))
        assertEquals(1, captionCpuLevel("processor\t: 0\n$x925\n\nprocessor\t: 1\n$a55\n"))
        assertEquals(0, captionCpuLevel(a53))
        assertEquals(0, captionCpuLevel(""))
    }

    @Test fun theFastestLibraryIsTriedFirstAndBaselineIsTheFallback() {
        val tried = mutableListOf<String>()
        assertEquals("whisper_jni_i8", loadCaptionLibrary(2) { tried += it })
        assertEquals("whisper_jni_fast", loadCaptionLibrary(1) { tried += it })
        assertEquals("whisper_jni", loadCaptionLibrary(0) { tried += it })
        assertEquals("whisper_jni_fast", loadCaptionLibrary(2) { tried += it; if (it.endsWith("i8")) throw UnsatisfiedLinkError(it) })
        assertEquals("whisper_jni", loadCaptionLibrary(2) { tried += it; if (it != "whisper_jni") throw UnsatisfiedLinkError(it) })
        assertEquals(listOf("whisper_jni_i8", "whisper_jni_fast", "whisper_jni", "whisper_jni_i8", "whisper_jni_fast",
            "whisper_jni_i8", "whisper_jni_fast", "whisper_jni"), tried)
    }
}
