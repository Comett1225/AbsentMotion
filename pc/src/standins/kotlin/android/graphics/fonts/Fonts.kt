package android.graphics.fonts

import java.io.File

class FontStyle(val weight: Int = 400, val slant: Int = 0)

class Font private constructor(val file: File?, val ttcIndex: Int, val style: FontStyle) {
    class Builder(private val file: File) {
        private var index = 0
        fun setTtcIndex(i: Int) = apply { index = i }
        fun setWeight(w: Int) = this
        fun setSlant(s: Int) = this
        fun build(): Font = Font(file, index, FontStyle())
    }
}

object SystemFonts {
    @JvmStatic fun getAvailableFonts(): Set<Font> = emptySet()
}
