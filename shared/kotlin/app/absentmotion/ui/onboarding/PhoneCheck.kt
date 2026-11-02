package app.absentmotion.ui.onboarding

import android.content.Context
import android.os.Build
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.Codec
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.DeviceReport

data class PhoneCheckResult(val formats: List<FormatSupport>, val speed: PhoneSpeed)

data class FormatSupport(val name: String, val maxShortSide: Int, val hardware: Boolean)

enum class PhoneSpeed { Fast, Good, Basic }

suspend fun runPhoneCheck(context: Context, engine: EngineThread?, progress: (Float) -> Unit): PhoneCheckResult =
    phoneCheckResult(DeviceCapabilities.of(context).runCheckNow(engine, progress), if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0)

private const val RealTime1080p = 1080.0 * 1920 * 30

internal fun phoneCheckResult(report: DeviceReport, performanceClass: Int): PhoneCheckResult {
    val formats = Codec.entries.map { codec ->
        val encoders = report.encodersFor(codec.mime)
        fun fits(side: Int, hardware: Boolean): Boolean {
            val long = (side * 16 / 9 + 1) and 1.inv()
            return encoders.any { (it.hardware || !hardware) && (it.fits(side, long, 30) || it.fits(long, side, 30)) }
        }
        val best = listOf(2160, 1440, 1080, 720).firstOrNull { fits(it, false) }
        FormatSupport(codec.label, best ?: 0, best != null && fits(best, true))
    }
    val measured = report.probe?.pixelsPerSecond
    val hardware = formats.filter { it.hardware }.maxOfOrNull { it.maxShortSide } ?: 0
    val cap = when {
        hardware >= 2160 -> PhoneSpeed.Fast
        hardware >= 1080 -> PhoneSpeed.Good
        else -> PhoneSpeed.Basic
    }
    val rated = when {
        measured != null -> if (measured >= RealTime1080p) PhoneSpeed.Fast else if (measured >= RealTime1080p / 3) PhoneSpeed.Good else PhoneSpeed.Basic
        performanceClass >= Build.VERSION_CODES.TIRAMISU -> PhoneSpeed.Fast
        performanceClass >= Build.VERSION_CODES.S -> PhoneSpeed.Good
        formats.any { it.hardware && it.maxShortSide >= 2160 } -> PhoneSpeed.Good
        else -> PhoneSpeed.Basic
    }
    return PhoneCheckResult(formats, maxOf(rated, cap))
}
