package android.graphics.fonts

import java.io.File

class FontStyle(val weight: Int = 400, val slant: Int = 0)

class Font private constructor(val file: File?, val ttcIndex: Int, val style: FontStyle) {
    val localeList: android.os.LocaleList get() = android.os.LocaleList.getEmptyLocaleList()
    class Builder(private val file: File) {
        private var index = 0
        fun setTtcIndex(i: Int) = apply { index = i }
        fun setWeight(w: Int) = this
        fun setSlant(s: Int) = this
        fun build(): Font = Font(file, index, SystemFonts.styleOf(file, index))
    }
}

object SystemFonts {
    private class Face(val family: String, val style: FontStyle)
    private val faces = java.util.concurrent.ConcurrentHashMap<String, List<Face>>()
    private fun weightOf(name: String): Int {
        val n = name.lowercase().replace(" ", "").replace("-", "")
        return when {
            "thin" in n || "hairline" in n -> 100
            "extralight" in n || "ultralight" in n -> 200
            "semibold" in n || "demibold" in n -> 600
            "extrabold" in n || "ultrabold" in n -> 800
            "black" in n || "heavy" in n -> 900
            "light" in n -> 300
            "medium" in n -> 500
            "bold" in n -> 700
            else -> 400
        }
    }
    private fun read(file: File): List<Face> = faces.getOrPut(file.path) {
        runCatching { java.awt.Font.createFonts(file).toList() }.getOrDefault(emptyList()).map { font ->
            val full = font.getFontName(java.util.Locale.ENGLISH)
            val family = font.getFamily(java.util.Locale.ENGLISH)
            val style = full.removePrefix(family)
            Face(family, FontStyle(weightOf(style), if ("italic" in full.lowercase() || "oblique" in full.lowercase()) 1 else 0))
        }
    }
    fun familyOf(file: File): String? = read(file).firstOrNull()?.family?.takeIf { it.isNotBlank() }
    fun styleOf(file: File, index: Int): FontStyle = read(file).getOrNull(index)?.style ?: FontStyle()
    private val available: Set<Font> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        val env = System.getenv()
        listOfNotNull("/System/Library/Fonts", "/System/Library/Fonts/Supplemental", "/Library/Fonts", "$home/Library/Fonts", "${env["WINDIR"] ?: "C:\\Windows"}\\Fonts",
            env["LOCALAPPDATA"]?.let { "$it\\Microsoft\\Windows\\Fonts" }, "/usr/share/fonts", "/usr/local/share/fonts", "$home/.fonts",
            "${env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share"}/fonts")
            .flatMap { File(it).walk().maxDepth(3).filter { file -> file.isFile }.toList() }
            .filter { it.extension.lowercase() in setOf("ttf", "otf", "ttc") && familyOf(it) != null }
            .flatMap { file -> read(file).indices.map { Font.Builder(file).setTtcIndex(it).build() } }.toSet()
    }
    @JvmStatic fun getAvailableFonts(): Set<Font> = available
}
