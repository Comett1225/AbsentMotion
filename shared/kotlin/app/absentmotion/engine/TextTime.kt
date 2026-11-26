package app.absentmotion.engine

import app.absentmotion.compat.ForeignEffectIds
import app.absentmotion.model.matchValues

const val FONT_WEIGHT_EFFECT = "absent.fontweight"
const val TEXT_SPACING_EFFECT = "absent.textspacing"
val TextSpacingEffects = setOf(TEXT_SPACING_EFFECT, ForeignEffectIds.TEXT_SPACING)
private val timedTextIds = TextSpacingEffects + FONT_WEIGHT_EFFECT

private val timedTextEffects = Regex("<effect\\b[^>]*\"(${timedTextIds.joinToString("|") { Regex.escape(it) }})\"[^>]*?(/>|>.*?</effect>)", RegexOption.DOT_MATCHES_ALL)

@Volatile private var lastTimedText: Pair<String, Boolean>? = null

fun animatesTextSpacing(xml: String): Boolean {
    lastTimedText?.takeIf { it.first === xml }?.let { return it.second }
    return (timedTextIds.any { it in xml } && timedTextEffects.matchValues(xml).any { it.contains("<kf") })
        .also { lastTimedText = xml to it }
}
