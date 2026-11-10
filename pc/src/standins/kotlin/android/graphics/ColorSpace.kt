package android.graphics

class ColorSpace private constructor() {
    enum class Named { SRGB }

    companion object {
        @JvmStatic fun get(name: Named): ColorSpace = ColorSpace()
    }
}
