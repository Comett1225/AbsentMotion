package app.absentmotion.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList(), @PluralsRes val plural: Int = 0, val count: Int = 0) {
    fun resolve(context: Context): String {
        val values = args.map { arg ->
            when (arg) {
                is UiText -> arg.resolve(context)
                is List<*> -> arg.joinToString(" ") { if (it is UiText) it.resolve(context) else "$it" }
                else -> arg
            }
        }.toTypedArray()
        return if (plural != 0) context.resources.getQuantityString(plural, count, *values) else context.getString(id, *values)
    }

    companion object {
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any) = UiText(0, args.toList(), id, count)
    }
}

fun txt(@StringRes id: Int, vararg args: Any) = UiText(id, args.toList())

@Composable
fun UiText.text(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}

object AppText {
    @Volatile
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext.inAppLanguage()
    }

    fun get(@StringRes id: Int, vararg args: Any): String = context?.getString(id, *args) ?: "#$id"

    fun get(text: UiText): String = context?.let(text::resolve) ?: "#${text.id}"
}
