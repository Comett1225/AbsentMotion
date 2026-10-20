package app.absentmotion.fonts

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.Uri

data class FontName(val source: String, val family: String, val weight: Int = 400, val italic: Boolean = false) {
    val xml: String get() = if (source == "imported") "imported?name=$family"
        else "googlefonts?name=$family&weight=$weight" + if (italic) "&italic=1" else ""
    val label: String get() = if (source == "imported") family.substringBeforeLast('.') else
        AppText.get(if (italic) R.string.library_font_label_italic else R.string.library_font_label, family, weights[weight]?.let { AppText.get(it) } ?: weight.toString())
    companion object {
        fun assetFolder(family: String) = family.replace(Regex("[^A-Za-z0-9]"), "")
        val Default = FontName("googlefonts", "Roboto")
        val weights = mapOf(
            100 to R.string.library_font_thin, 200 to R.string.library_font_extra_light, 300 to R.string.library_font_light, 400 to R.string.library_font_regular,
            500 to R.string.library_font_medium, 600 to R.string.library_font_semi_bold, 700 to R.string.library_font_bold, 800 to R.string.library_font_extra_bold,
            900 to R.string.library_font_black,
        )
        fun parse(value: String): FontName {
            if (value.isEmpty()) return Default
            val uri = Uri.parse("font://" + value.replaceFirst("?", "/?"))
            return FontName(value.substringBefore('?'), uri.getQueryParameter("name") ?: "Roboto",
                uri.getQueryParameter("weight")?.toIntOrNull()?.coerceIn(100, 900) ?: 400,
                uri.getQueryParameter("italic") == "1")
        }
    }
}
