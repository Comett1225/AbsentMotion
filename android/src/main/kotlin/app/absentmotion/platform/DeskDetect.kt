package app.absentmotion.platform

import android.app.Activity
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import app.absentmotion.ui.adaptive.DeskLayout

data class DisplayFacts(
    val dex: Boolean = false,
    val samsung: Boolean = false,
    val deskMode: Boolean = false,
    val otherDisplay: Boolean = false,
)

object DeskDetect {
    var forced = false

    fun wanted(facts: DisplayFacts): Boolean = facts.dex || facts.samsung && (facts.deskMode || facts.otherDisplay)

    fun roomy(widthDp: Int, heightDp: Int): Boolean = widthDp >= 820 && heightDp >= 560

    fun samsung(manufacturer: String?): Boolean = manufacturer.equals("samsung", ignoreCase = true)

    fun dexOn(type: Class<*>, config: Any): Boolean = runCatching {
        type.getField("semDesktopModeEnabled").getInt(config) == type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
    }.getOrDefault(false)

    fun facts(activity: Activity): DisplayFacts = DisplayFacts(
        dex = dexOn(Configuration::class.java, activity.resources.configuration),
        samsung = samsung(Build.MANUFACTURER),
        deskMode = runCatching { (activity.getSystemService(Activity.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_DESK }.getOrDefault(false),
        otherDisplay = runCatching { displayId(activity) != Display.DEFAULT_DISPLAY }.getOrDefault(false),
    )

    @Suppress("DEPRECATION")
    private fun displayId(activity: Activity): Int =
        if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId ?: Display.DEFAULT_DISPLAY else activity.windowManager.defaultDisplay.displayId

    fun refresh(activity: Activity) {
        DeskLayout.detected = forced || wanted(facts(activity)) && activity.resources.configuration.let { roomy(it.screenWidthDp, it.screenHeightDp) }
    }
}
