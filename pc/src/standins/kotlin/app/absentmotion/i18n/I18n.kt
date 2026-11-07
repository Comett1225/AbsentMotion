package app.absentmotion.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import app.absentmotion.desk.Strings

data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList(), @PluralsRes val plural: Int = 0, val count: Int = 0) {
    fun resolve(context: Context? = null): String {
        val values = args.map { arg ->
            when (arg) {
                is UiText -> arg.resolve(context)
                is List<*> -> arg.joinToString(" ") { if (it is UiText) it.resolve(context) else "$it" }
                else -> arg
            }
        }.toTypedArray()
        return if (plural != 0) Strings.plural(plural, count, *values) else Strings.get(id, *values)
    }

    companion object {
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any) = UiText(0, args.toList(), id, count)
    }
}

fun txt(@StringRes id: Int, vararg args: Any) = UiText(id, args.toList())

@Composable
fun UiText.text(): String = resolve()

object AppText {
    fun install(context: Context) {}
    fun get(@StringRes id: Int, vararg args: Any): String = Strings.get(id, *args)
    fun get(text: UiText): String = text.resolve()
}

fun failureText(summary: String, failure: Throwable): String {
    val detail = failure.message?.trim().orEmpty()
    return if (detail.isEmpty() || detail == summary) summary else "$summary\n$detail"
}

enum class AppLanguage(val tag: String, val nativeName: String) {
    English("en", "English"),
    Indonesian("id", "Bahasa Indonesia"),
    Hindi("hi", "हिन्दी"),
    Portuguese("pt-BR", "Português (Brasil)"),
    Spanish("es", "Español (Latinoamérica)"),
    SpanishSpain("es-ES", "Español (España)"),
    Filipino("fil", "Filipino"),
    Vietnamese("vi", "Tiếng Việt"),
    Thai("th", "ไทย"),
    Malay("ms", "Bahasa Melayu"),
    Arabic("ar", "العربية"),
    Persian("fa", "فارسی"),
    Urdu("ur", "اردو"),
    Turkish("tr", "Türkçe"),
    Russian("ru", "Русский"),
    Bengali("bn", "বাংলা"),
    Japanese("ja", "日本語"),
    Korean("ko", "한국어"),
    ChineseTraditional("zh-TW", "繁體中文"),
    ChineseSimplified("zh-CN", "简体中文"),
    French("fr", "Français"),
    German("de", "Deutsch"),
    Italian("it", "Italiano"),
    Polish("pl", "Polski"),
    Ukrainian("uk", "Українська");

    val language: String get() = tag.substringBefore('-')
    val region: String? get() = tag.substringAfter('-', "").ifEmpty { null }

    companion object {
        fun match(tags: List<String>): AppLanguage? = tags.firstNotNullOfOrNull { tag ->
            val parts = tag.trim().replace('_', '-').split('-')
            val language = canonical(parts[0].lowercase())
            val region = parts.drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
                .let { if (language == "zh") chineseRegion(parts.drop(1).firstOrNull { it.length == 4 }, it) else it }
            entries.firstOrNull { it.language == language && it.region != null && it.region == region }
                ?: entries.firstOrNull { it.language == language && it.region == null }
                ?: entries.firstOrNull { it.language == language }
        }

        fun preselect(phoneTags: List<String>): AppLanguage = match(phoneTags) ?: English

        fun suggestedOrder(top: AppLanguage): List<AppLanguage> = listOf(top) + entries.filter { it != top }

        private fun chineseRegion(script: String?, region: String?) =
            if (script?.lowercase() == "hant" || (script == null && region in setOf("TW", "HK", "MO"))) "TW" else "CN"

        private fun canonical(language: String) = when (language) {
            "in" -> "id"
            "tl" -> "fil"
            "zsm" -> "ms"
            "pes", "prs" -> "fa"
            else -> language
        }
    }
}

fun phoneLanguage(context: Context): AppLanguage = AppLanguage.preselect(listOf(java.util.Locale.getDefault().toLanguageTag()))

fun currentLanguage(context: Context): AppLanguage = AppLanguage.match(listOfNotNull(context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", null))) ?: AppLanguage.English

internal fun chosenTags(appLocales: List<String>?, legacy: String?): List<String> = appLocales?.ifEmpty { null } ?: listOfNotNull(legacy)

internal fun legacyToMove(appLocales: List<String>, legacy: String?): String? = legacy?.takeIf { appLocales.isEmpty() }

fun keepLegacyLanguage(context: Context) {}

fun applyLanguage(context: Context, language: AppLanguage) {
    context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("tag", language.tag).commit()
}

fun Context.inAppLanguage(): Context = this

@Composable
fun Ltr(content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalLayoutDirection provides LayoutDirection.Ltr,
    LocalTextStyle provides LocalTextStyle.current.copy(textAlign = TextAlign.Left, textDirection = TextDirection.Content),
    content = content,
)
