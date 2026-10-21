package app.absentmotion.ai

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode

const val AI_TAG = "ai"

enum class AiKind(val xml: String, @StringRes val title: Int, val attribute: String?) {
    Noise("noise", R.string.ai_reduce_noise, "src"),
    Background("background", R.string.ai_remove_background, "fillImage"),
    Video("video", R.string.ai_remove_background_video, null),
}

enum class NoiseSource(val xml: String, @StringRes val title: Int, @StringRes val hint: Int, val rumbleHz: Double, val blend: Double) {
    Voice("voice", R.string.ai_noise_voice, R.string.ai_noise_voice_hint, 0.0, 1.0),
    Wind("wind", R.string.ai_noise_wind, R.string.ai_noise_wind_hint, 150.0, 1.0),
    Mixed("mixed", R.string.ai_noise_mixed, R.string.ai_noise_mixed_hint, 0.0, 0.5),
}

class AiMark(val node: XmlNode) {
    val kind: AiKind? get() = AiKind.entries.firstOrNull { it.xml == node["type"] }
    val source: NoiseSource? get() = NoiseSource.entries.firstOrNull { it.xml == node["option"] }
    val original: String? get() = node["original"]
    val result: String? get() = node["result"]
}

val Layer.aiMarks: List<AiMark> get() = node.children.filter { it.name == AI_TAG }.map(::AiMark)

fun Layer.withAiMark(kind: AiKind, original: String?, result: String?, option: String? = null): Layer {
    val first = aiMarks.firstOrNull { it.kind == kind }?.original ?: original
    val attrs = linkedMapOf("type" to kind.xml)
    first?.let { attrs["original"] = it }
    result?.let { attrs["result"] = it }
    option?.let { attrs["option"] = it }
    return copy(node = node.copy(children = node.children.filterNot { it.name == AI_TAG && it["type"] == kind.xml } + XmlNode(AI_TAG, attrs)))
}

val AiMark.restorable: Boolean get() = kind?.attribute != null && original != null

fun Layer.withoutAiMark(kind: AiKind): Layer {
    val mark = aiMarks.firstOrNull { it.kind == kind } ?: return this
    val key = kind.attribute
    val restored = if (key != null && mark.original != null && node[key] == mark.result) node.with(key, mark.original) else node
    return copy(node = restored.copy(children = restored.children.filterNot { it.name == AI_TAG && it["type"] == kind.xml }))
}
