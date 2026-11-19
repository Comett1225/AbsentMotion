package app.absentmotion.fonts

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionFaceTest {
    @Test fun theFaceForTheAppLanguageIsFoundInsideAFontCollection() {
        assertTrue(sameWriting(Locale.forLanguageTag("zh-Hans"), Locale.forLanguageTag("zh-CN")))
        assertTrue(sameWriting(Locale.forLanguageTag("zh-Hant"), Locale.forLanguageTag("zh-TW")))
        assertFalse(sameWriting(Locale.forLanguageTag("zh-Hant"), Locale.forLanguageTag("zh-CN")))
        assertTrue(sameWriting(Locale.forLanguageTag("ko"), Locale.forLanguageTag("ko-KR")))
        assertFalse(sameWriting(Locale.forLanguageTag("ja"), Locale.forLanguageTag("zh-CN")))
        assertFalse(sameWriting(Locale.forLanguageTag("ja"), Locale.ENGLISH))
    }
}
