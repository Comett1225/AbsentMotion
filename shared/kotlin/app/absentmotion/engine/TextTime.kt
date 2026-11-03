package app.absentmotion.engine

import app.absentmotion.compat.ForeignEffectIds
import app.absentmotion.model.matchValues

const val FONT_WEIGHT_EFFECT = "absent.fontweight"

private val timedTextEffects = Regex("<effect\\b[^>]*\"(${Regex.escape(ForeignEffectIds.TEXT_SPACING)}|${Regex.escape(FONT_WEIGHT_EFFECT)})\"[^>]*?(/>|>.*?</effect>)", RegexOption.DOT_MATCHES_ALL)

fun animatesTextSpacing(xml: String): Boolean =
    (xml.contains(ForeignEffectIds.TEXT_SPACING) || xml.contains(FONT_WEIGHT_EFFECT)) && timedTextEffects.matchValues(xml).any { it.contains("<kf") }
