package androidx.core.view

class WindowInsetsControllerCompat(window: Any?, view: Any?) {
    var isAppearanceLightStatusBars = false
    var isAppearanceLightNavigationBars = false
}

object WindowCompat {
    @JvmStatic fun getInsetsController(window: Any?, view: Any?) = WindowInsetsControllerCompat(window, view)
    @JvmStatic fun setDecorFitsSystemWindows(window: Any?, fits: Boolean) {}
}
