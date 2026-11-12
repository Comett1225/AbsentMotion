package app.absentmotion.platform

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

enum class HapticRoute { None, View, Vibrator }

fun hapticRoute(appVibration: Boolean, systemTouch: Boolean): HapticRoute = when {
    systemTouch -> HapticRoute.View
    appVibration -> HapticRoute.Vibrator
    else -> HapticRoute.None
}

fun hapticIsClick(type: HapticFeedbackType): Boolean = type == HapticFeedbackType.LongPress

private fun systemTouch(context: Context): Boolean =
    runCatching { Settings.System.getInt(context.contentResolver, "haptic_feedback_enabled", 1) != 0 }.getOrDefault(true)

private fun vibrate(context: Context, click: Boolean) {
    runCatching {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(if (click) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(if (click) 20L else 10L, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        else vibrator.vibrate(effect)
    }
}

fun View.appHaptic(click: Boolean) {
    if (systemTouch(context)) performHapticFeedback(if (click) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
    else vibrate(context, click)
}

class AppHaptics(private val context: Context, private val system: HapticFeedback, private val appVibration: () -> Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticRoute(appVibration(), systemTouch(context))) {
            HapticRoute.View -> system.performHapticFeedback(hapticFeedbackType)
            HapticRoute.Vibrator -> vibrate(context, hapticIsClick(hapticFeedbackType))
            HapticRoute.None -> Unit
        }
    }
}
