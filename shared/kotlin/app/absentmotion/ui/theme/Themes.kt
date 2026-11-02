package app.absentmotion.ui.theme

import androidx.compose.ui.graphics.Color
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Context
import org.json.JSONObject
import java.io.File

class Theme(val name: String?, val palette: Palette)

class ThemeError(val text: UiText) : IllegalArgumentException(text.toString())

object ThemeFile {
    fun parseColour(text: String): Color? {
        if (!text.startsWith("#") || (text.length != 7 && text.length != 9)) return null
        val hex = text.substring(1)
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val value = hex.toLong(16)
        return Color(if (hex.length == 6) 0xFF000000L or value else value)
    }

    fun parse(text: String): Theme {
        val json = try { JSONObject(text.removePrefix("\uFEFF")) } catch (e: Exception) { throw ThemeError(txt(R.string.settings_theme_not_theme_file)) }
        val colours = HashMap<String, Color>()
        for (key in Palette.Names) {
            if (!json.has(key)) continue
            colours[key] = (json.get(key) as? String)?.let(::parseColour) ?: throw ThemeError(txt(R.string.theme_error_bad_colour, key))
        }
        if (colours.isEmpty()) throw ThemeError(txt(R.string.theme_error_no_colours))
        val name = (json.opt("name") as? String)?.trim()?.take(MAX_NAME)?.ifEmpty { null }
        return Theme(name, Palette.from(colours))
    }

    private const val MAX_NAME = 40
}

object Themes {
    const val CUSTOM = "custom"
    val DEFAULT = Palette.BuiltIn.first().first
    const val MAX_BYTES = 64 * 1024

    private fun customFile(context: Context) = File(context.filesDir, "themes/custom.json")

    fun custom(context: Context): Theme? = runCatching { ThemeFile.parse(customFile(context).readText()) }.getOrNull()

    fun palette(context: Context, id: String): Palette =
        if (id == CUSTOM) custom(context)?.palette ?: Palette.AbsentMotion
        else Palette.BuiltIn.firstOrNull { it.first == id }?.second ?: Palette.AbsentMotion

    fun import(context: Context, text: String): Theme {
        val theme = ThemeFile.parse(text)
        val file = customFile(context)
        app.absentmotion.library.writeAtomically(file, text, sync = false)
        return theme
    }
}
