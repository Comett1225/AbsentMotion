package app.absentmotion.effects

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow

internal enum class EffectTargetKind { Photo, Video, Text, Shape, Group, Model }

internal enum class ScreenColor { Green, Blue }

internal data class MediaLook(val screen: ScreenColor? = null, val dark: Boolean = false, val cutout: Boolean = false)

internal data class EffectRecommendationTarget(
    val kind: EffectTargetKind,
    val animated: Boolean = false,
    val effects: Set<String> = emptySet(),
    val hiddenEffects: Set<String> = emptySet(),
    val look: MediaLook = MediaLook(),
    val short: Boolean = false,
    val beats: Boolean = false,
)

private data class RecommendationRule(
    val key: String,
    val preferred: Set<EffectTargetKind>,
    val family: String? = null,
    val needsMotion: Boolean = false,
    val alongside: Set<String> = emptySet(),
    val needs: (EffectRecommendationTarget) -> Boolean = { true },
    val boost: (EffectRecommendationTarget) -> Int = { 0 },
)

private data class RankedEffect(val effect: EffectDefinition, val family: String, val score: Double, val habit: Double, val fits: Boolean)

internal data class EffectUse(val weight: Double, val last: Long, val skipped: Int = 0)

private const val HabitHalfLifeMs = 30L * 24 * 60 * 60 * 1000
private const val HabitSkipCost = 0.6
private const val MaxSkips = 5
private const val MaxPerKind = 40
private const val StrongHabit = 2.5

internal class EffectHabits(val uses: Map<EffectTargetKind, Map<String, EffectUse>> = emptyMap()) {
    private fun faded(use: EffectUse, now: Long) = use.weight * 0.5.pow((now - use.last).coerceAtLeast(0L).toDouble() / HabitHalfLifeMs)

    fun strength(kind: EffectTargetKind, id: String, now: Long): Double =
        uses[kind]?.get(id)?.let { faded(it, now) / (1 + HabitSkipCost * it.skipped) } ?: 0.0

    fun used(kinds: Collection<EffectTargetKind>, id: String, now: Long) =
        change(kinds) { old -> old + (id to EffectUse((old[id]?.let { faded(it, now) } ?: 0.0) + 1, now)) }

    fun ignored(kinds: Collection<EffectTargetKind>, ids: Collection<String>) =
        change(kinds) { old -> old + ids.mapNotNull { id -> old[id]?.let { id to it.copy(skipped = minOf(it.skipped + 1, MaxSkips)) } } }

    private fun change(kinds: Collection<EffectTargetKind>, edit: (Map<String, EffectUse>) -> Map<String, EffectUse>) = EffectHabits(
        uses + kinds.toSet().associateWith { kind -> edit(uses[kind].orEmpty()).entries.sortedByDescending { it.value.last }.take(MaxPerKind).associate { it.key to it.value } },
    )

    fun encode(): String = JSONObject().also { root ->
        uses.forEach { (kind, map) -> root.put(kind.name, JSONObject().also { o -> map.forEach { (id, u) -> o.put(id, JSONArray().put(u.weight).put(u.last).put(u.skipped)) } }) }
    }.toString()

    companion object {
        fun decode(text: String?): EffectHabits = runCatching {
            val root = JSONObject(text ?: return EffectHabits())
            EffectHabits(EffectTargetKind.entries.mapNotNull { kind ->
                val o = root.optJSONObject(kind.name) ?: return@mapNotNull null
                kind to o.keys().asSequence().mapNotNull { id ->
                    val a = o.optJSONArray(id) ?: return@mapNotNull null
                    val weight = a.optDouble(0, 0.0).takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
                    id to EffectUse(weight, a.optLong(1, 0L), a.optInt(2, 0).coerceIn(0, MaxSkips))
                }.toMap()
            }.toMap())
        }.getOrDefault(EffectHabits())
    }
}

private val PhotoAndVideo = setOf(EffectTargetKind.Photo, EffectTargetKind.Video)
private val FootageAndGroups = PhotoAndVideo + EffectTargetKind.Group
private val Artwork = setOf(EffectTargetKind.Text, EffectTargetKind.Shape)
private val MotionEffects = setOf("absent.oscillate", "absent.swing", "absent.autoshake", "absent.movealongpath")

private val RecommendationRules = listOf(
    RecommendationRule("directionalblur", FootageAndGroups + EffectTargetKind.Shape, needsMotion = true, alongside = MotionEffects),
    RecommendationRule("chromakey", PhotoAndVideo, needs = { it.look.screen != null }, boost = { 10 }),
    RecommendationRule("beatshake", FootageAndGroups + Artwork, needs = { it.beats }, boost = { 7 }),
    RecommendationRule("beatzoom", FootageAndGroups, needs = { it.beats }, boost = { 6 }),
    RecommendationRule("beatflash", PhotoAndVideo, needs = { it.beats }, boost = { 5 }),
    RecommendationRule("dropshadow", setOf(EffectTargetKind.Photo), needs = { it.look.cutout }, boost = { 6 }),
    RecommendationRule("textshake", setOf(EffectTargetKind.Text), family = "shake", needs = { it.short || it.beats }, boost = { 5 }),
    RecommendationRule("autoshake", FootageAndGroups + Artwork, family = "shake", needs = { it.short }, boost = { 4 }),
    RecommendationRule("exposure", FootageAndGroups + EffectTargetKind.Model, alongside = setOf("absent.vignette"), boost = { if (it.look.dark) 5 else 0 }),
    RecommendationRule("sharpen", PhotoAndVideo, family = "detail", alongside = setOf("absent.exposure")),
    RecommendationRule("vignette", FootageAndGroups + EffectTargetKind.Model, alongside = setOf("absent.exposure")),
    RecommendationRule("glow", Artwork, alongside = setOf("absent.texttransform", "absent.textprogress")),
    RecommendationRule("texttransform", setOf(EffectTargetKind.Text)),
    RecommendationRule("pulseopacity", Artwork),
    RecommendationRule("huesaturation", PhotoAndVideo + EffectTargetKind.Shape),
    RecommendationRule("brightnesscontrast", FootageAndGroups),
    RecommendationRule("temperature", PhotoAndVideo),
    RecommendationRule("colorbalance", PhotoAndVideo),
    RecommendationRule("softglow", Artwork + EffectTargetKind.Group),
    RecommendationRule("textprogress", setOf(EffectTargetKind.Text)),
    RecommendationRule("feather", setOf(EffectTargetKind.Shape)),
    RecommendationRule("pulsesize", Artwork),
    RecommendationRule("oscillate", setOf(EffectTargetKind.Shape, EffectTargetKind.Group)),
    RecommendationRule("swing", setOf(EffectTargetKind.Shape, EffectTargetKind.Group)),
    RecommendationRule("blur", setOf(EffectTargetKind.Group)),
    RecommendationRule("tiles", setOf(EffectTargetKind.Shape), alongside = setOf("absent.directionalblur")),
    RecommendationRule("solid", setOf(EffectTargetKind.Shape)),
)

internal fun recommendedEffects(
    definitions: List<EffectDefinition>,
    targets: List<EffectRecommendationTarget>,
    recent: List<String>,
    favourites: Set<String>,
    habits: EffectHabits = EffectHabits(),
    now: Long = 0L,
): List<EffectDefinition> {
    if (targets.isEmpty()) return emptyList()
    val available = definitions.filter { it.available && it.shortcut == null }.associateBy { it.id }
    val applied = targets.flatMapTo(mutableSetOf()) { it.effects + it.hiddenEffects }
    val allText = targets.all { it.kind == EffectTargetKind.Text }
    val animated = targets.all { it.animated || it.effects.any(MotionEffects::contains) }
    val recentIds = recent.take(8).toSet()
    fun habit(id: String) = targets.map { habits.strength(it.kind, id, now) }.average().let { if (it < 0.05) 0.0 else minOf(7.0, 2.5 * ln(1 + it)) }
    fun extra(id: String) = (if (id in favourites) 1 else 0) + (if (id in recentIds) 1 else 0)
    fun allowed(effect: EffectDefinition) = effect.id !in applied && !(effect.category == "text" && !allText)
    val ruled = RecommendationRules.mapNotNull { rule ->
        val effect = available["absent.${rule.key}"] ?: return@mapNotNull null
        if (!allowed(effect) || rule.needsMotion && !animated || !targets.all(rule.needs)) return@mapNotNull null
        val context = if (targets.all { it.kind in rule.preferred }) 4 else 0
        val pairing = if (rule.alongside.isNotEmpty() && targets.all { it.effects.any(rule.alongside::contains) }) 2 else 0
        val learned = habit(effect.id)
        RankedEffect(effect, rule.family ?: effect.category ?: return@mapNotNull null, context + pairing + extra(effect.id) + targets.minOf(rule.boost) + learned, learned, true)
    }
    val ruledIds = ruled.mapTo(HashSet()) { it.effect.id }
    val learnedOnly = targets.flatMapTo(LinkedHashSet()) { habits.uses[it.kind].orEmpty().keys }.mapNotNull { id ->
        val effect = available[id]?.takeIf { it.id !in ruledIds && allowed(it) } ?: return@mapNotNull null
        val learned = habit(id).takeIf { it > 0 } ?: return@mapNotNull null
        RankedEffect(effect, effect.category ?: return@mapNotNull null, learned + extra(id), learned, false)
    }
    val ranked = (ruled + learnedOnly).sortedByDescending { it.score }
    val picks = ArrayList<RankedEffect>(3)
    fun take(candidates: List<RankedEffect>, diverse: Boolean = true) = candidates.forEach { r ->
        if (picks.size < 3 && r !in picks && (!diverse || picks.none { it.family == r.family })) picks += r
    }
    take(ranked.filter { it.habit >= StrongHabit }.take(2), diverse = false)
    take(ranked.filter { it.fits })
    take(ranked)
    take(ranked, diverse = false)
    return picks.map { it.effect }
}

internal fun mediaLook(pixels: IntArray, width: Int, height: Int): MediaLook {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return MediaLook()
    val band = maxOf(1, minOf(width, height) / 5)
    val edge = IntArray(2)
    val green = IntArray(2)
    val blue = IntArray(2)
    var clear = 0
    var luma = 0.0
    for (y in 0 until height) for (x in 0 until width) {
        val c = pixels[y * width + x]
        if (c ushr 24 < 16) { clear++; continue }
        val r = (c shr 16 and 255) / 255f
        val g = (c shr 8 and 255) / 255f
        val b = (c and 255) / 255f
        luma += 0.2126 * r + 0.7152 * g + 0.0722 * b
        if (x >= band && x < width - band && y >= band && y < height - band) continue
        val half = if (y < height / 2) 0 else 1
        edge[half]++
        val max = maxOf(r, g, b)
        if (max < 0.2f || (max - minOf(r, g, b)) / max < 0.45f) continue
        if (g == max && g - maxOf(r, b) > 0.2f) green[half]++
        else if (b == max && b - maxOf(r, g) > 0.25f) blue[half]++
    }
    fun covers(counts: IntArray, share: Float) = (0..1).all { edge[it] > 0 && counts[it] >= edge[it] * share }
    val screen = when {
        covers(green, 0.4f) -> ScreenColor.Green
        covers(blue, 0.5f) -> ScreenColor.Blue
        else -> null
    }
    val opaque = width * height - clear
    return MediaLook(screen, dark = opaque > 0 && luma / opaque < 0.22, cutout = clear >= width * height * 0.08)
}

internal fun screenFromName(uri: String): ScreenColor? {
    val name = uri.substringAfterLast('/').lowercase().filter { it in 'a'..'z' }
    return when {
        "greenscreen" in name || "chromakey" in name -> ScreenColor.Green
        "bluescreen" in name -> ScreenColor.Blue
        else -> null
    }
}
