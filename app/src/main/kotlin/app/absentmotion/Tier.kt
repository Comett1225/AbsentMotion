package app.absentmotion

import androidx.annotation.StringRes
import app.absentmotion.export.Choice
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt

enum class Feature(@StringRes val label: Int, val inCompatible: Boolean) {
    FlowHigh(R.string.feature_flow_high, inCompatible = false),
}

object Tier {
    val compatible: Boolean = BuildConfig.COMPATIBLE

    fun has(feature: Feature): Boolean = !compatible || feature.inCompatible

    val leftOut: List<Feature> get() = Feature.entries.filterNot(::has)

    fun reason(feature: Feature): UiText? = if (has(feature)) null else txt(R.string.tier_needs_latest)

    fun <T> choice(value: T, feature: Feature): Choice<T> = Choice(value, reason(feature), locked = !has(feature))
}
