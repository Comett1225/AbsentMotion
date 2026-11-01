package android.graphics

import java.io.File

class Typeface private constructor(val file: File?, val family: String?, val weight: Int, val italic: Boolean, val index: Int = 0) {
    val style: Int get() = (if (weight >= 600) BOLD else 0) or (if (italic) ITALIC else 0)
    val isBold: Boolean get() = weight >= 600
    val isItalic: Boolean get() = italic

    class Builder(private val file: File) {
        private var index = 0
        private var weight = 400
        private var italic = false
        constructor(path: String) : this(File(path))
        fun setTtcIndex(i: Int) = apply { index = i }
        fun setFontVariationSettings(s: String?) = apply { s?.let { Regex("'wght'\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()?.let { w -> weight = w } } }
        fun setWeight(w: Int) = apply { weight = w }
        fun setItalic(i: Boolean) = apply { italic = i }
        fun build(): Typeface? = if (file.isFile && file.length() > 0 && validFont(file)) Typeface(file, null, weight, italic, index) else null
    }

    companion object {
        const val NORMAL = 0; const val BOLD = 1; const val ITALIC = 2; const val BOLD_ITALIC = 3
        @JvmField val DEFAULT = Typeface(null, "sans-serif", 400, false)
        @JvmField val DEFAULT_BOLD = Typeface(null, "sans-serif", 700, false)
        @JvmField val SANS_SERIF = DEFAULT
        @JvmField val SERIF = Typeface(null, "serif", 400, false)
        @JvmField val MONOSPACE = Typeface(null, "monospace", 400, false)
        @JvmStatic fun create(family: String?, style: Int) = Typeface(null, family, if (style and BOLD != 0) 700 else 400, style and ITALIC != 0)
        @JvmStatic fun create(face: Typeface?, style: Int) = Typeface(face?.file, face?.family, if (style and BOLD != 0) 700 else face?.weight ?: 400, style and ITALIC != 0, face?.index ?: 0)
        @JvmStatic fun create(face: Typeface?, weight: Int, italic: Boolean) = Typeface(face?.file, face?.family, weight, italic, face?.index ?: 0)
        @JvmStatic fun createFromFile(file: File) = Builder(file).build() ?: DEFAULT
        @JvmStatic fun createFromFile(path: String) = createFromFile(File(path))
        private fun validFont(file: File): Boolean = runCatching {
            file.inputStream().use { val b = ByteArray(4); it.read(b); val tag = String(b, Charsets.ISO_8859_1); tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO" || tag == "true" || tag == "ttcf" || tag == "wOFF" || tag == "wOF2" }
        }.getOrDefault(false)
    }
}
