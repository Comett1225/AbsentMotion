package app.absentmotion.i18n

import app.absentmotion.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLanguageTest {
    private fun pick(vararg tags: String) = AppLanguage.preselect(tags.toList())

    @Test fun thePhoneLanguageIsPreselected() {
        assertEquals(AppLanguage.English, pick("en-US"))
        assertEquals(AppLanguage.English, pick("en-GB"))
        assertEquals(AppLanguage.Hindi, pick("hi-IN"))
        assertEquals(AppLanguage.Vietnamese, pick("vi-VN"))
        assertEquals(AppLanguage.Thai, pick("th-TH"))
        assertEquals(AppLanguage.Turkish, pick("tr-TR"))
        assertEquals(AppLanguage.Russian, pick("ru-RU"))
        assertEquals(AppLanguage.Arabic, pick("ar-EG"))
        assertEquals(AppLanguage.Arabic, pick("ar-SA-u-nu-latn"))
    }

    @Test fun otherRegionsFallBackToTheLanguageWeHave() {
        assertEquals(AppLanguage.Portuguese, pick("pt-PT"))
        assertEquals(AppLanguage.Portuguese, pick("pt-BR"))
        assertEquals(AppLanguage.Portuguese, pick("pt"))
        assertEquals(AppLanguage.Spanish, pick("es-ES"))
        assertEquals(AppLanguage.Spanish, pick("es-419"))
        assertEquals(AppLanguage.Spanish, pick("es_MX"))
        assertEquals(AppLanguage.Malay, pick("ms-SG"))
    }

    @Test fun oldAndAlternativeCodesAreUnderstood() {
        assertEquals(AppLanguage.Indonesian, pick("in-ID"))
        assertEquals(AppLanguage.Indonesian, pick("id-ID"))
        assertEquals(AppLanguage.Filipino, pick("fil-PH"))
        assertEquals(AppLanguage.Filipino, pick("tl-PH"))
        assertEquals(AppLanguage.Hindi, pick("HI"))
    }

    @Test fun unsupportedLanguagesFallBackToTheNextPhoneLanguageThenEnglish() {
        assertEquals(AppLanguage.English, pick("zh-CN"))
        assertEquals(AppLanguage.English, pick("zh-Hant-TW", "ja-JP"))
        assertEquals(AppLanguage.Russian, pick("zh-CN", "ru-RU", "en-US"))
        assertEquals(AppLanguage.English, pick())
        assertEquals(AppLanguage.English, pick(""))
        assertNull(AppLanguage.match(listOf("zh-CN", "ko-KR")))
    }

    @Test fun theSuggestedLanguageComesFirstAndEveryLanguageOnce() {
        for (top in AppLanguage.entries) {
            val order = AppLanguage.suggestedOrder(top)
            assertEquals(top, order.first())
            assertEquals(AppLanguage.entries.toSet(), order.toSet())
            assertEquals(AppLanguage.entries.size, order.size)
        }
        assertEquals(AppLanguage.entries, AppLanguage.suggestedOrder(AppLanguage.English))
    }

    @Test fun twelveLanguagesEachKnowTheirOwnName() {
        assertEquals(12, AppLanguage.entries.size)
        assertEquals(12, AppLanguage.entries.map { it.nativeName }.toSet().size)
        assertEquals("العربية", AppLanguage.Arabic.nativeName)
        assertEquals("हिन्दी", AppLanguage.Hindi.nativeName)
        assertEquals("Bahasa Indonesia", AppLanguage.Indonesian.nativeName)
        for (language in AppLanguage.entries) assertEquals(language, AppLanguage.match(listOf(language.tag)))
    }

    private val res = TestData.project.resolve("app/src/main/res")

    private fun folder(language: AppLanguage) = when (language) {
        AppLanguage.English -> "values"
        AppLanguage.Indonesian -> "values-in"
        else -> "values-" + language.tag.replace("-", "-r")
    }

    private fun strings(file: File): Map<String, String> =
        Regex("""<string name="([a-z0-9_]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .filter { "translatable=\"false\"" !in it.groupValues[2] }.associate { it.groupValues[1] to it.groupValues[3] }

    private fun plurals(file: File): Map<String, Map<String, String>> =
        Regex("""<plurals name="([a-z0-9_]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).associate { p ->
            p.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(p.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2] }
        }

    @Test fun everyLanguageHasEveryStringWithTheSamePlaceholders() {
        val english = strings(File(res, "values/strings.xml"))
        assertTrue(english.isNotEmpty())
        val placeholders = Regex("""%(\d+\$)?[sdf]""")
        for (language in AppLanguage.entries) {
            val file = File(res, folder(language) + "/strings.xml")
            assertTrue("missing $file", file.isFile)
            val local = strings(file)
            assertEquals("keys differ in ${file.parentFile.name}", english.keys, local.keys)
            for ((key, text) in english) {
                assertEquals("placeholders differ in ${file.parentFile.name} $key",
                    placeholders.findAll(text).map { it.value }.sorted().toList(), placeholders.findAll(local.getValue(key)).map { it.value }.sorted().toList())
                assertTrue("empty ${file.parentFile.name} $key", local.getValue(key).isNotBlank())
                assertTrue("unescaped apostrophe in ${file.parentFile.name} $key", !Regex("""(?<!\\)'""").containsMatchIn(local.getValue(key)))
            }
        }
        val englishPlurals = plurals(File(res, "values/strings.xml"))
        for (language in AppLanguage.entries) {
            val file = File(res, folder(language) + "/strings.xml")
            val local = plurals(file)
            assertEquals("plurals differ in ${file.parentFile.name}", englishPlurals.keys, local.keys)
            for ((key, forms) in englishPlurals) {
                val wanted = placeholders.findAll(forms.getValue("other")).map { it.value }.toSet()
                val got = local.getValue(key)
                assertTrue("no other form in ${file.parentFile.name} $key", "other" in got)
                assertEquals("placeholders differ in ${file.parentFile.name} $key", wanted, placeholders.findAll(got.getValue("other")).map { it.value }.toSet())
                for ((q, text) in got) {
                    assertTrue("${file.parentFile.name} $key $q", text.isNotBlank() && wanted.containsAll(placeholders.findAll(text).map { it.value }.toSet()))
                    assertTrue("unescaped apostrophe in ${file.parentFile.name} $key", !Regex("""(?<!\\)'""").containsMatchIn(text))
                }
            }
        }
        val folders = res.listFiles()!!.filter { File(it, "strings.xml").isFile }.map { it.name }.toSet()
        assertEquals(AppLanguage.entries.map(::folder).toSet(), folders)
    }

    @Test fun theSystemLanguageListMatchesTheAppLanguages() {
        val config = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(AppLanguage.entries.map { it.tag }, listed)
    }
}
