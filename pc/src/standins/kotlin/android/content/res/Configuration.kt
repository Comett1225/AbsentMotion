package android.content.res

class Configuration() {
    constructor(other: Configuration) : this() { screenWidthDp = other.screenWidthDp; screenHeightDp = other.screenHeightDp; uiMode = other.uiMode; fontScale = other.fontScale }
    var uiMode = UI_MODE_NIGHT_YES
    val locales: android.os.LocaleList get() = android.os.LocaleList.getDefault()
    fun setLocales(list: android.os.LocaleList?) {}
    fun setLocale(locale: java.util.Locale?) {}
    var screenWidthDp = 1280
    var screenHeightDp = 800
    var smallestScreenWidthDp = 800
    var orientation = 2
    var fontScale = 1f
    var densityDpi = 320
    companion object { const val ORIENTATION_PORTRAIT = 1; const val ORIENTATION_LANDSCAPE = 2; const val UI_MODE_NIGHT_MASK = 48; const val UI_MODE_NIGHT_NO = 16; const val UI_MODE_NIGHT_YES = 32 }
}
