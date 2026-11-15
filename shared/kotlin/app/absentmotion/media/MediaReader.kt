package app.absentmotion.media

import app.absentmotion.platform.Context
import app.absentmotion.platform.PlatformFeatures

internal const val FFAMP_PREF = "mediaFfamp"

object MediaReader {
    @Volatile private var chosen: Boolean? = null

    fun ffamp(context: Context): Boolean =
        chosen ?: (PlatformFeatures.ffamp && context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(FFAMP_PREF, false)).also { chosen = it }

    @Synchronized fun useNow(wanted: Boolean): Boolean {
        val value = PlatformFeatures.ffamp && wanted
        if (chosen == null) chosen = value
        return chosen == value
    }
}
