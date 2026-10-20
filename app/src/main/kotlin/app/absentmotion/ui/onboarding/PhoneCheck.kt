package app.absentmotion.ui.onboarding

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import app.absentmotion.export.Codec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhoneCheckResult(val formats: List<FormatSupport>, val speed: PhoneSpeed)

data class FormatSupport(val name: String, val maxShortSide: Int, val hardware: Boolean)

enum class PhoneSpeed { Fast, Good, Basic }

suspend fun runPhoneCheck(context: Context, progress: (Float) -> Unit): PhoneCheckResult = withContext(Dispatchers.Default) {
    val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
    val sizes = listOf(2160 to 3840, 1440 to 2560, 1080 to 1920, 720 to 1280)
    val formats = Codec.entries.mapIndexed { i, codec ->
        progress(i.toFloat() / Codec.entries.size)
        val found = encoders.filter { info -> info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } }
        fun fits(info: android.media.MediaCodecInfo, short: Int, long: Int) = runCatching {
            val video = info.getCapabilitiesForType(codec.mime).videoCapabilities ?: return@runCatching false
            video.areSizeAndRateSupported(long, short, 30.0) || video.areSizeAndRateSupported(short, long, 30.0)
        }.getOrDefault(false)
        val best = sizes.firstOrNull { (short, long) -> found.any { fits(it, short, long) } }
        val hardware = best != null && found.any { it.isHardwareAccelerated && fits(it, best.first, best.second) }
        FormatSupport(codec.label, best?.first ?: 0, hardware)
    }
    progress(1f)
    val speed = when {
        Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.TIRAMISU -> PhoneSpeed.Fast
        Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S -> PhoneSpeed.Good
        formats.any { it.hardware && it.maxShortSide >= 2160 } -> PhoneSpeed.Good
        else -> PhoneSpeed.Basic
    }
    PhoneCheckResult(formats, speed)
}
