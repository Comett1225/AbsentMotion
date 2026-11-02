package app.absentmotion.fonts

object BundledFonts {
    fun pick(font: FontName, names: Set<String>, exact: Boolean = true): String? {
        if (font.source != "googlefonts") return null
        val folder = FontName.assetFolder(font.family)
        val suffix = if (font.italic) "Italic" else ""
        val style = when (font.weight) { 400 -> if (font.italic) "Italic" else "Regular"; 700 -> if (font.italic) "BoldItalic" else "Bold"; else -> null }
        listOf("$folder/$folder-$style.ttf", "$folder/$folder-Variable$suffix.ttf").firstOrNull { it in names }?.let { return it }
        if (exact) return null
        val own = names.filter { it.startsWith("$folder/") }
        val sameSlant = own.filter { it.endsWith("Italic.ttf") == font.italic }.ifEmpty { own }
        return sameSlant.minByOrNull { kotlin.math.abs((if ("Bold" in it) 700 else 400) - font.weight) }
    }
}
