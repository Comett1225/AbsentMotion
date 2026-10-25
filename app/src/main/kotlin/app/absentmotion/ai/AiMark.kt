package app.absentmotion.ai

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode

const val AI_TAG = "ai"

enum class AiKind(val xml: String, @StringRes val title: Int, val attribute: String?, val ai: Boolean = true) {
    Noise("noise", R.string.ai_reduce_noise, "src"),
    Background("background", R.string.ai_remove_background, "fillImage"),
    Video("video", R.string.ai_remove_background_video, null),
    Smooth("smooth", R.string.smooth_clip, "fillVideo", false),
    Enhance("enhance", R.string.ai_enhance, "fillVideo"),
}

enum class NoiseSource(val xml: String, @StringRes val title: Int, @StringRes val hint: Int, val rumbleHz: Double, val blend: Double) {
    Voice("voice", R.string.ai_noise_voice, R.string.ai_noise_voice_hint, 0.0, 1.0),
    Wind("wind", R.string.ai_noise_wind, R.string.ai_noise_wind_hint, 150.0, 1.0),
    Mixed("mixed", R.string.ai_noise_mixed, R.string.ai_noise_mixed_hint, 0.0, 0.5),
}

class AiMark(val node: XmlNode) {
    val kind: AiKind? get() = AiKind.entries.firstOrNull { it.xml == node["type"] }
    val source: NoiseSource? get() = NoiseSource.entries.firstOrNull { it.xml == node["option"] }
    val enhance: EnhanceOptions? get() = if (kind == AiKind.Enhance) EnhanceOptions.parse(node["option"]) else null
    val original: String? get() = node["original"]
    val result: String? get() = node["result"]
    val shift: Int get() = node["shift"]?.toIntOrNull() ?: 0
    val media: MediaDeclaration? get() = node.child("media")?.let(::MediaDeclaration)
}

val Layer.aiMarks: List<AiMark> get() = node.children.filter { it.name == AI_TAG }.map(::AiMark)

fun Layer.withAiMark(kind: AiKind, original: String?, result: String?, option: String? = null, media: MediaDeclaration? = null, shift: Int = 0,
                     extra: Map<String, String> = emptyMap()): Layer {
    val previous = aiMarks.firstOrNull { it.kind == kind }
    val first = previous?.original ?: original
    val attrs = linkedMapOf("type" to kind.xml)
    first?.let { attrs["original"] = it }
    result?.let { attrs["result"] = it }
    option?.let { attrs["option"] = it }
    if (shift != 0) attrs["shift"] = "$shift"
    attrs.putAll(extra)
    val kept = (if (previous?.original != null) previous.media else media)?.takeIf { it.uri == first }
    return copy(node = node.copy(children = node.children.filterNot { it.name == AI_TAG && it["type"] == kind.xml } + XmlNode(AI_TAG, attrs, listOfNotNull(kept?.node))))
}

val AiMark.restorable: Boolean get() = kind?.attribute != null && original != null

internal fun Layer.aiStack(kind: AiKind): List<AiMark> {
    val key = kind.attribute ?: return emptyList()
    val marks = aiMarks.filter { it.kind?.attribute == key }
    var top = marks.firstOrNull { it.kind == kind } ?: return emptyList()
    val stack = mutableListOf(top)
    while (top.result != node[key]) {
        val built = top
        top = marks.firstOrNull { next -> next !in stack && next.original == built.result } ?: return emptyList()
        stack += top
    }
    return stack
}

internal fun Layer.aiBuried(kind: AiKind): Boolean = aiStack(kind).size > 1

internal fun Layer.shiftedMedia(by: Int): Layer {
    val fill = mediaFill
    if (by == 0 || fill == null) return this
    val moved = node.with("inTime", "${(fill.inTime.toLong() + by).coerceAtLeast(0)}")
    return copy(node = if (fill.outTime == Int.MAX_VALUE) moved else moved.with("outTime", "${(fill.outTime.toLong() + by).coerceIn(0L, Int.MAX_VALUE - 1L)}"))
}

fun Layer.withoutAiMark(kind: AiKind): Layer {
    val mark = aiMarks.firstOrNull { it.kind == kind } ?: return this
    val key = kind.attribute
    val stack = aiStack(kind)
    val back = if (key != null && mark.original != null && stack.isNotEmpty()) Layer(node.with(key, mark.original)).shiftedMedia(stack.sumOf { it.shift }).node else node
    val gone = (stack.ifEmpty { listOf(mark) }).map { it.node }.toSet()
    return copy(node = back.copy(children = back.children.filterNot { it.name == AI_TAG && it in gone }))
}

fun Scene.withoutAiMark(id: Long, kind: AiKind): Scene {
    val mark = layer(id)?.aiMarks?.firstOrNull { it.kind == kind } ?: return this
    return (mark.media?.let(::withMedia) ?: this).update(id) { it.withoutAiMark(kind) }
}
