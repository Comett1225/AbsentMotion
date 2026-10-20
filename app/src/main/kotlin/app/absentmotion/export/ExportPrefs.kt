package app.absentmotion.export

import android.content.Context
import app.absentmotion.model.Scene
import org.json.JSONObject

class ExportPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("export_settings", Context.MODE_PRIVATE)

    fun load(kind: ExportKind, scene: Scene): ExportSettings {
        val fallback = defaultSettings(kind, scene)
        val saved = prefs.getString(kind.name, null)?.let { runCatching { settingsFromJson(JSONObject(it), fallback) }.getOrNull() } ?: return fallback
        return if (kind == ExportKind.Video && saved.shortSide > fallback.shortSide) saved.copy(shortSide = fallback.shortSide) else saved
    }

    fun save(kind: ExportKind, settings: ExportSettings) {
        prefs.edit().putString(kind.name, settingsToJson(settings).toString()).apply()
    }
}

internal fun settingsToJson(s: ExportSettings): JSONObject = JSONObject()
    .put("shortSide", s.shortSide).put("fps", s.fps).put("codec", s.codec.name).put("quality", s.quality.toDouble())
    .put("image", s.image.name).put("imageQuality", s.imageQuality).put("background", s.background)
    .put("interpolation", s.interpolation.name).put("container", s.container.name).put("audioFormat", s.audioFormat.name)
    .put("audioCodec", s.audio.codec.name).put("audioKbps", s.audio.bitrateKbps).put("audioRate", s.audio.sampleRate)
    .put("audioChannels", s.audio.channels)

internal fun settingsFromJson(o: JSONObject, fallback: ExportSettings): ExportSettings {
    fun <E : Enum<E>> pick(key: String, values: List<E>, default: E): E = values.firstOrNull { it.name == o.optString(key) } ?: default
    val audio = AudioSettings(
        pick("audioCodec", AudioCodec.entries, fallback.audio.codec),
        o.optInt("audioKbps", fallback.audio.bitrateKbps).takeIf { it in AudioBitratesKbps } ?: fallback.audio.bitrateKbps,
        o.optInt("audioRate", fallback.audio.sampleRate).takeIf { it in AudioSampleRates } ?: fallback.audio.sampleRate,
        o.optInt("audioChannels", fallback.audio.channels).takeIf { it in 1..2 } ?: fallback.audio.channels,
    )
    return ExportSettings(
        o.optInt("shortSide", fallback.shortSide).takeIf { it > 0 } ?: fallback.shortSide,
        o.optInt("fps", fallback.fps).takeIf { it > 0 } ?: fallback.fps,
        pick("codec", Codec.entries, fallback.codec),
        o.optDouble("quality", fallback.quality.toDouble()).toFloat().takeIf { it in 0f..1f } ?: fallback.quality,
        pick("image", ImageKind.entries, fallback.image),
        o.optInt("imageQuality", fallback.imageQuality).takeIf { it in 1..100 } ?: fallback.imageQuality,
        o.optInt("background", fallback.background),
        pick("interpolation", FrameInterpolation.entries, fallback.interpolation),
        pick("container", Container.entries, fallback.container),
        audio,
        pick("audioFormat", AudioFormat.entries, fallback.audioFormat),
    ).fixedFormat()
}
