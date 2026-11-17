package app.absentmotion.platform

import android.app.Activity
import android.app.UiModeManager
import android.content.res.Configuration
import android.view.InputDevice
import app.absentmotion.ui.adaptive.DeskLayout

data class DisplayFacts(
    val dex: Boolean = false,
    val deskMode: Boolean = false,
    val externalDisplay: Boolean = false,
    val freeform: Boolean = false,
    val widthDp: Int = 0,
    val finePointer: Boolean = false,
)

object DeskDetect {
    const val FREEFORM_MIN_DP = 900
    private const val WINDOWING_MODE_FREEFORM = 5

    fun wanted(facts: DisplayFacts): Boolean =
        facts.dex || facts.deskMode || facts.externalDisplay || (facts.freeform && facts.widthDp >= FREEFORM_MIN_DP && facts.finePointer)

    fun dexOn(type: Class<*>, config: Any): Boolean = runCatching {
        type.getField("semDesktopModeEnabled").getInt(config) == type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
    }.getOrDefault(false)

    fun freeform(config: Any): Boolean = runCatching {
        val window = config.javaClass.getField("windowConfiguration").get(config)!!
        window.javaClass.getMethod("getWindowingMode").invoke(window) as Int == WINDOWING_MODE_FREEFORM
    }.getOrDefault(false)

    private fun finePointer(): Boolean = runCatching {
        InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.let { it.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE } == true }
    }.getOrDefault(false)

    fun facts(activity: Activity): DisplayFacts {
        val config = activity.resources.configuration
        return DisplayFacts(
            dex = dexOn(Configuration::class.java, config),
            deskMode = runCatching { (activity.getSystemService(Activity.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_DESK }.getOrDefault(false),
            externalDisplay = runCatching { (activity.display?.displayId ?: 0) != 0 }.getOrDefault(false),
            freeform = freeform(config),
            widthDp = config.screenWidthDp,
            finePointer = finePointer(),
        )
    }

    fun refresh(activity: Activity) {
        DeskLayout.detected = wanted(facts(activity))
    }
}
