package app.absentmotion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
        val json = try { JSONObject(text.removePrefix("\uFEFF")) } catch (e: Exception) { throw ThemeError(txt(R.string.settings_theme_not_theme_file)) } catch (e: StackOverflowError) { throw ThemeError(txt(R.string.settings_theme_not_theme_file)) }
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
    const val SYSTEM = "system"
    val DEFAULT = Palette.BuiltIn.first().first
    const val MAX_BYTES = 64 * 1024

    private fun customFile(context: Context) = File(context.filesDir, "themes/custom.json")

    fun custom(context: Context): Theme? = runCatching { ThemeFile.parse(customFile(context).readText()) }.getOrNull()

    fun resolve(id: String, systemDark: Boolean): String = if (id != SYSTEM) id else if (systemDark) "Dark" else DEFAULT

    fun firstChoice(saved: String?, onboarding: Int, lookChosen: Boolean): String = saved ?: if (onboarding == 0 && !lookChosen) SYSTEM else DEFAULT

    fun palette(context: Context, id: String, systemDark: Boolean = false): Palette = palette(context, resolve(id, systemDark))

    private fun palette(context: Context, id: String): Palette =
        if (id == CUSTOM) custom(context)?.palette ?: Palette.AbsentMotion
        else Palette.BuiltIn.firstOrNull { it.first == id }?.second ?: Palette.AbsentMotion

    fun import(context: Context, text: String): Theme {
        val theme = ThemeFile.parse(text)
        val file = customFile(context)
        app.absentmotion.library.writeAtomically(file, text, sync = false)
        return theme
    }
}

object Accents {
    val Presets: List<Int> = listOf(0xFF3B82F6.toInt(), 0xFF22A861.toInt(), 0xFF9B6BFF.toInt(), 0xFFE8508F.toInt())
    private val Dark = Color(0xFF17181B)

    fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    fun on(colour: Color): Color = if (contrast(colour, Dark) >= contrast(colour, Color.White)) Dark else Color.White

    private fun hueAndSaturation(c: Color): Pair<Float, Float> {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        if (d <= 0f) return 0f to 0f
        val l = (max + min) / 2f
        val s = d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when (max) {
            c.red -> 60f * (((c.green - c.blue) / d).mod(6f))
            c.green -> 60f * ((c.blue - c.red) / d + 2f)
            else -> 60f * ((c.red - c.green) / d + 4f)
        }
        return h to s.coerceIn(0f, 1f)
    }

    private fun lightness(c: Color): Float = (maxOf(c.red, c.green, c.blue) + minOf(c.red, c.green, c.blue)) / 2f

    fun shade(accent: Color, like: Color): Color {
        val (h, s) = hueAndSaturation(accent)
        return Color.hsl(h, s, lightness(like), like.alpha)
    }

    fun apply(palette: Palette, accent: Int?): Palette {
        if (accent == null) return palette
        val c = Color(accent).copy(alpha = 1f)
        return palette.copy(
            Accent = c, HomeAccent = c, OnAccent = on(c),
            AccentLit = shade(c, palette.AccentLit), AccentGlow = shade(c, palette.AccentGlow), AccentDeep = shade(c, palette.AccentDeep),
            OnNavyAccent = shade(c, palette.OnNavyAccent),
        )
    }
}
