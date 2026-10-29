package app.absentmotion

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.export.Choice
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt

enum class Feature(@StringRes val label: Int, val inCompatible: Boolean) {
    FlowHigh(R.string.feature_flow_high, inCompatible = false),
    AiSmooth(R.string.export_interpolation_ai, inCompatible = false),
    AiEnhance(R.string.feature_ai_enhance, inCompatible = false),
    BestCutout(R.string.feature_best_cutout, inCompatible = false),
}

object Tier {
    val compatible: Boolean = BuildConfig.COMPATIBLE
    var openGl by mutableStateOf(false)

    fun has(feature: Feature): Boolean = (!compatible && !openGl) || feature.inCompatible

    val leftOut: List<Feature> get() = Feature.entries.filterNot(::has)

    fun reason(feature: Feature): UiText? = if (has(feature)) null else txt(if (compatible) R.string.tier_needs_latest else R.string.tier_needs_vulkan)

    fun <T> choice(value: T, feature: Feature): Choice<T> = Choice(value, reason(feature), locked = !has(feature))
}
