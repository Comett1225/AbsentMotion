package app.absentmotion.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
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

@RequiresApi(33)
private fun Context.locales() = getSystemService(LocaleManager::class.java)

private fun Context.chosen() = getSharedPreferences("language", Context.MODE_PRIVATE)

fun phoneLanguage(context: Context): AppLanguage = AppLanguage.preselect(
    (if (Build.VERSION.SDK_INT >= 33) context.locales().systemLocales else Resources.getSystem().configuration.locales).tags())

fun currentLanguage(context: Context): AppLanguage = AppLanguage.match(
    if (Build.VERSION.SDK_INT >= 33) context.locales().applicationLocales.tags() else listOfNotNull(context.chosen().getString("tag", null)))
    ?: phoneLanguage(context)

fun applyLanguage(context: Context, language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.locales().applicationLocales = LocaleList.forLanguageTags(language.tag)
        return
    }
    context.chosen().edit().putString("tag", language.tag).commit()
    var c: Context? = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    (c as? Activity)?.recreate()
}

fun Context.inAppLanguage(): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    val tag = chosen().getString("tag", null) ?: return this
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
}

@Composable
fun Ltr(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
