package app.absentmotion

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.absentmotion.export.Choice
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt

enum class Feature(@StringRes val label: Int, val inCompatible: Boolean) {
    FlowHigh(R.string.feature_flow_high, inCompatible = false),
    AiSmooth(R.string.export_interpolation_ai, inCompatible = false),
    AiEnhance(R.string.feature_ai_enhance, inCompatible = false),
    BestCutout(R.string.feature_best_cutout, inCompatible = false),
}

internal fun <T> stateReadableFromOpenSnapshots(value: T) = Snapshot.global { Snapshot.withMutableSnapshot { mutableStateOf(value) } }

object Tier {
    val compatible: Boolean = BuildConfig.COMPATIBLE
    var openGl by stateReadableFromOpenSnapshots(false)

    fun has(feature: Feature): Boolean = (!compatible && !openGl) || feature.inCompatible

    val leftOut: List<Feature> get() = Feature.entries.filterNot(::has)

    fun reason(feature: Feature): UiText? = if (has(feature)) null else txt(if (compatible) R.string.tier_needs_latest else R.string.tier_needs_vulkan)

    fun <T> choice(value: T, feature: Feature): Choice<T> = Choice(value, reason(feature), risky = !has(feature))

    @Volatile var unlocked: Set<Feature> = emptySet()

    fun runs(feature: Feature): Boolean = has(feature) || feature in unlocked

    fun risk(feature: Feature): Risk? = reason(feature)?.let { Risk(feature, it) }

    suspend fun <T> unlocking(features: Collection<Feature>, block: suspend () -> T): T {
        unlocked = unlocked + features
        try { return block() } finally { unlocked = unlocked - features.toSet() }
    }
}

data class Risk(val key: Any, val reason: UiText)

class RiskAsk(val risks: List<Risk>, val proceed: () -> Unit)

class Unlock {
    private var confirmed = emptySet<Any>()

    fun confirm(risks: List<Risk>) { confirmed = confirmed + risks.map { it.key } }

    fun pending(risks: List<Risk>): List<Risk> = risks.filter { it.key !in confirmed }

    fun spend() { confirmed = emptySet() }
}
