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
        fun build(): Font = Font(file, index, FontStyle())
    }
}

object SystemFonts {
    private val available: Set<Font> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        val families = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.map { family -> family.lowercase().filter { it.isLetterOrDigit() } }.toSet() }.getOrDefault(emptySet())
        val env = System.getenv()
        listOfNotNull("/System/Library/Fonts", "/System/Library/Fonts/Supplemental", "/Library/Fonts", "$home/Library/Fonts", "${env["WINDIR"] ?: "C:\\Windows"}\\Fonts",
            env["LOCALAPPDATA"]?.let { "$it\\Microsoft\\Windows\\Fonts" }, "/usr/share/fonts", "/usr/local/share/fonts", "$home/.fonts",
            "${env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share"}/fonts")
            .flatMap { File(it).walk().maxDepth(3).filter { file -> file.isFile }.toList() }
            .filter { it.extension.lowercase() in setOf("ttf", "otf", "ttc") && it.nameWithoutExtension.substringBefore('-').lowercase().filter { c -> c.isLetterOrDigit() } in families }
            .mapNotNull { runCatching { Font.Builder(it).build() }.getOrNull() }.toSet()
    }
    @JvmStatic fun getAvailableFonts(): Set<Font> = available
}
