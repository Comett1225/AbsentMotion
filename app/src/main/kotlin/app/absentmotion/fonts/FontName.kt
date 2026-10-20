package app.absentmotion.fonts

import app.absentmotion.platform.Uri

data class FontName(val source: String, val family: String, val weight: Int = 400, val italic: Boolean = false) {
    val xml: String get() = if (source == "imported") "imported?name=$family"
        else "googlefonts?name=$family&weight=$weight" + if (italic) "&italic=1" else ""
    val label: String get() = if (source == "imported") family.substringBeforeLast('.') else
        "$family ${weights[weight] ?: weight}" + if (italic) " Italic" else ""
    companion object {
        fun assetFolder(family: String) = family.replace(Regex("[^A-Za-z0-9]"), "")
        val Default = FontName("googlefonts", "Roboto")
        val weights = mapOf(100 to "Thin", 200 to "Extra Light", 300 to "Light", 400 to "Regular", 500 to "Medium",
            600 to "Semi Bold", 700 to "Bold", 800 to "Extra Bold", 900 to "Black")
        fun parse(value: String): FontName {
            if (value.isEmpty()) return Default
            val uri = Uri.parse("font://" + value.replaceFirst("?", "/?"))
            return FontName(value.substringBefore('?'), uri.getQueryParameter("name") ?: "Roboto",
                uri.getQueryParameter("weight")?.toIntOrNull()?.coerceIn(100, 900) ?: 400,
                uri.getQueryParameter("italic") == "1")
        }
    }
}
