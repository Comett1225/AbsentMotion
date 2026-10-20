package app.absentmotion.i18n

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

enum class AppLanguage(val tag: String, val nativeName: String) {
    English("en", "English"),
    Indonesian("id", "Bahasa Indonesia"),
    Hindi("hi", "हिन्दी"),
    Portuguese("pt-BR", "Português (Brasil)"),
    Spanish("es", "Español"),
    Filipino("fil", "Filipino"),
    Vietnamese("vi", "Tiếng Việt"),
    Thai("th", "ไทย"),
    Malay("ms", "Bahasa Melayu"),
    Arabic("ar", "العربية"),
    Turkish("tr", "Türkçe"),
    Russian("ru", "Русский");

    val language: String get() = tag.substringBefore('-')

    companion object {
        fun match(tags: List<String>): AppLanguage? = tags.firstNotNullOfOrNull { tag ->
            val language = canonical(tag.trim().substringBefore('-').substringBefore('_').lowercase())
            entries.firstOrNull { it.language == language }
        }

        fun preselect(phoneTags: List<String>): AppLanguage = match(phoneTags) ?: English

        fun suggestedOrder(top: AppLanguage): List<AppLanguage> = listOf(top) + entries.filter { it != top }

        private fun canonical(language: String) = when (language) {
            "in" -> "id"
            "tl" -> "fil"
            "zsm" -> "ms"
            else -> language
        }
    }
}

private fun LocaleList.tags() = (0 until size()).map { get(it).toLanguageTag() }

private fun Context.locales() = getSystemService(LocaleManager::class.java)

fun phoneLanguage(context: Context): AppLanguage = AppLanguage.preselect(context.locales().systemLocales.tags())

fun currentLanguage(context: Context): AppLanguage = AppLanguage.match(context.locales().applicationLocales.tags()) ?: phoneLanguage(context)

fun applyLanguage(context: Context, language: AppLanguage) {
    context.locales().applicationLocales = LocaleList.forLanguageTags(language.tag)
}

@Composable
fun Ltr(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
