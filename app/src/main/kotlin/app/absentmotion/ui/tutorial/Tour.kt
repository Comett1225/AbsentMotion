package app.absentmotion.ui.tutorial

import androidx.annotation.StringRes
import app.absentmotion.R
import kotlin.math.abs

data class TourFacts(
    val editing: Boolean = false,
    val media: Int = 0,
    val mediaMs: Int = 0,
    val texts: Int = 0,
    val audio: Int = 0,
    val selected: String? = null,
    val panel: String = TOOLS,
    val adding: Boolean = false,
    val frame: Int = 0,
    val playing: Boolean = false,
    val moves: Int = 0,
    val keyframes: Int = 0,
    val eased: Int = 0,
    val effects: Int = 0,
    val tune: Int = 0,
    val motions: Int = 0,
    val undoMark: Long = 0,
    val exporting: Boolean = false,
    val browsing: Boolean = false,
    val easing: Boolean = false,
    val between: Boolean = false,
    val keypad: Boolean = false,
) {
    val visual: Int get() = media + texts
    val visualSelected: Boolean get() = selected != null && selected != AUDIO
    val textEditing: Boolean get() = panel == EDIT_TEXT && selected == TEXT

    companion object {
        const val TOOLS = "Tools"
        const val TRANSFORM = "MoveTransform"
        const val EFFECTS = "Effects"
        const val EDIT_TEXT = "EditText"
        const val MEDIA = "media"
        const val TEXT = "text"
        const val AUDIO = "audio"
        const val OTHER = "other"
    }
}

enum class TourAction { Pause, Unselect, SamplePicture, SampleText, SampleSound, Move, Scrub, Split, Keyframes, Ease, Effect, Animation, Export }

class TourStep(
    val id: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val seconds: Int,
    val targets: (start: TourFacts, now: TourFacts) -> List<String> = { _, _ -> emptyList() },
    val hint: (start: TourFacts, now: TourFacts) -> Int? = { _, _ -> null },
    val done: ((start: TourFacts, now: TourFacts) -> Boolean)? = null,
    val stage: ((start: TourFacts, now: TourFacts) -> Boolean)? = null,
    val doIt: List<TourAction> = emptyList(),
    val needs: (TourFacts) -> List<TourAction> = { emptyList() },
    val prepare: List<TourAction> = emptyList(),
    val sample: TourAction? = null,
    @StringRes val sampleNote: Int = 0,
    val centred: Boolean = false,
    val union: Boolean = false,
    val look: Boolean = false,
    val moving: Boolean = false,
    val extra: (start: TourFacts, now: TourFacts) -> List<String> = { _, _ -> emptyList() },
    val deny: List<String> = emptyList(),
    val free: (start: TourFacts, now: TourFacts) -> Boolean = { _, _ -> false },
)

class Tour(val steps: List<TourStep> = TourSteps.all, startAt: String? = null) {
    var index: Int = steps.indexOfFirst { it.id == startAt }.coerceAtLeast(0)
        private set
    @StringRes var note: Int? = null
        private set
    var finished: Boolean = false
        private set
    private var begun: TourFacts? = null
    private var staged: TourFacts? = null
    private var last: TourFacts? = null
    private var prepared = false
    private var assisted = false
    private var helped = false
    private var repairs = 0

    val step: TourStep get() = steps[index]
    val start: TourFacts get() = begun ?: last ?: TourFacts()
    val now: TourFacts get() = last ?: TourFacts()
    val canDoIt: Boolean get() = step.doIt.isNotEmpty()

    fun targets(): List<String> = step.targets(start, now)

    fun allowed(): List<String> = if (step.look) emptyList() else (step.targets(start, now) + step.extra(start, now)).distinct()

    fun denied(): List<String> = step.deny

    fun free(): Boolean = now.keypad || step.free(start, now)

    @StringRes fun hint(): Int? = step.hint(start, now)

    fun observe(facts: TourFacts): List<TourAction> {
        val before = last
        last = facts
        if (finished || !facts.editing) return emptyList()
        if (!prepared) {
            prepared = true
            if (step.prepare.isNotEmpty()) return step.prepare
        }
        val start = begun ?: facts.also { begun = it }
        if (facts.playing && !step.moving && repairs < MAX_REPAIRS) {
            repairs++
            return listOf(TourAction.Pause)
        }
        val missing = step.needs(facts)
        if (missing.isNotEmpty()) {
            if (repairs >= MAX_REPAIRS) return emptyList()
            repairs++
            begun = null
            staged = null
            note = R.string.tutorial_note_restored
            return missing
        }
        val stage = step.stage
        if (stage != null && staged == null && stage(start, facts)) staged = facts
        val middle = staged
        val complete = when {
            stage == null -> step.done?.invoke(start, facts) == true
            middle == null -> false
            else -> helped || step.done?.invoke(middle, facts) == true
        }
        if (complete) {
            advance(if (assisted) note else R.string.tutorial_note_nice)
            return observe(facts)
        }
        val sample = step.sample
        if (sample != null && !assisted && before?.editing == true && before.adding && !facts.adding) {
            assisted = true
            note = step.sampleNote
            return listOf(sample)
        }
        return emptyList()
    }

    fun next() = advance(null)

    fun doIt(): List<TourAction> {
        if (finished) return emptyList()
        assisted = true
        helped = true
        note = null
        return if (staged != null) emptyList() else step.doIt
    }

    fun pickerClosed(picked: Boolean): List<TourAction> {
        val sample = step.sample
        if (finished || picked || sample == null || assisted || last?.editing != true) return emptyList()
        assisted = true
        note = step.sampleNote
        return listOf(sample)
    }

    private fun advance(@StringRes shown: Int?) {
        note = shown
        if (index >= steps.lastIndex) {
            finished = true
            return
        }
        index++
        begun = null
        staged = null
        prepared = false
        assisted = false
        helped = false
        repairs = 0
    }

    companion object {
        const val MAX_REPAIRS = 2
    }
}

object TourSteps {
    private fun pick(now: TourFacts, adding: List<String>): List<String> = when {
        now.adding -> adding
        now.selected != null -> listOf("back")
        else -> listOf("add")
    }

    private fun unselectHint(now: TourFacts): Int? = if (!now.adding && now.selected != null) R.string.tutorial_hint_unselect else null

    private fun selectHint(now: TourFacts): Int? = when {
        !now.visualSelected -> R.string.tutorial_hint_select
        now.panel != TourFacts.TOOLS -> R.string.tutorial_hint_tools
        else -> null
    }

    private val needsPicture: (TourFacts) -> List<TourAction> = { if (it.visual == 0) listOf(TourAction.SamplePicture) else emptyList() }

    val all: List<TourStep> = listOf(
        TourStep("welcome", R.string.tutorial_welcome_title, R.string.tutorial_welcome_body, 10, centred = true),
        TourStep("screen", R.string.tutorial_screen_title, R.string.tutorial_screen_body, 10, targets = { _, _ -> listOf("playbar", "timeline") }, union = true, look = true),
        TourStep(
            "picture", R.string.tutorial_picture_title, R.string.tutorial_picture_body, 25,
            targets = { _, n -> pick(n, listOf("add_tab_media", "add_media")) },
            union = true,
            hint = { _, n -> unselectHint(n) },
            done = { s, n -> n.media > s.media },
            doIt = listOf(TourAction.SamplePicture),
            prepare = listOf(TourAction.Unselect),
            sample = TourAction.SamplePicture, sampleNote = R.string.tutorial_note_sample_picture,
        ),
        TourStep(
            "gestures", R.string.tutorial_gestures_title, R.string.tutorial_gestures_body, 20,
            targets = { _, n -> if (n.selected == TourFacts.MEDIA) listOf("preview") else listOf("clip_media") },
            deny = listOf("transition", "animate", "side_panel"),
            hint = { _, n -> if (n.selected != TourFacts.MEDIA) R.string.tutorial_hint_select else null },
            done = { s, n -> n.moves != s.moves && n.media > 0 },
            doIt = listOf(TourAction.Move),
            needs = { if (it.media == 0) listOf(TourAction.SamplePicture) else emptyList() },
        ),
        TourStep(
            "timeline", R.string.tutorial_timeline_title, R.string.tutorial_timeline_body, 15,
            targets = { _, _ -> listOf("timeline", "play", "skip_back", "skip_forward") },
            deny = listOf("tools", "panel", "add", "add_panel", "transition"),
            union = true,
            moving = true,
            done = { s, n -> abs(n.frame - s.frame) >= 3 || n.playing },
            doIt = listOf(TourAction.Scrub),
            needs = { if (it.media == 0) listOf(TourAction.SamplePicture) else emptyList() },
        ),
        TourStep(
            "split", R.string.tutorial_split_title, R.string.tutorial_split_body, 20,
            targets = { _, n ->
                when {
                    n.selected != TourFacts.MEDIA -> listOf("clip_media")
                    n.panel != TourFacts.TOOLS -> listOf("back")
                    else -> listOf("tool_split")
                }
            },
            extra = { _, n -> if (n.selected == TourFacts.MEDIA && n.panel == TourFacts.TOOLS) listOf("ruler") else emptyList() },
            hint = { _, n -> if (n.selected != TourFacts.MEDIA) R.string.tutorial_hint_select else if (n.panel != TourFacts.TOOLS) R.string.tutorial_hint_tools else null },
            done = { s, n -> n.media > s.media },
            doIt = listOf(TourAction.Split),
            needs = { if (it.media == 0) listOf(TourAction.SamplePicture) else emptyList() },
        ),
        TourStep(
            "text", R.string.tutorial_text_title, R.string.tutorial_text_body, 25,
            targets = { _, n -> if (n.textEditing) listOf("text_field", "text_done") else pick(n, listOf("add_text")) },
            hint = { _, n -> if (n.textEditing) null else unselectHint(n) },
            done = { s, n -> n.texts > s.texts && !n.textEditing },
            doIt = listOf(TourAction.SampleText),
            prepare = listOf(TourAction.Unselect),
        ),
        TourStep(
            "keyframes", R.string.tutorial_keyframes_title, R.string.tutorial_keyframes_body, 30,
            targets = { s, n ->
                when {
                    !n.visualSelected -> listOf("clip")
                    n.panel == TourFacts.TRANSFORM -> if (n.keyframes > s.keyframes) listOf("ruler", "pad") else listOf("keyframe")
                    n.panel == TourFacts.TOOLS -> listOf("tool_transform")
                    else -> listOf("back")
                }
            },
            hint = { s, n ->
                when {
                    !n.visualSelected -> R.string.tutorial_hint_select
                    n.panel == TourFacts.TRANSFORM -> if (n.keyframes > s.keyframes) R.string.tutorial_hint_second_keyframe else null
                    n.panel == TourFacts.TOOLS -> null
                    else -> R.string.tutorial_hint_tools
                }
            },
            done = { s, n -> n.keyframes >= s.keyframes + 2 },
            doIt = listOf(TourAction.Keyframes),
            needs = needsPicture,
        ),
        TourStep(
            "easing", R.string.tutorial_easing_title, R.string.tutorial_easing_body, 15,
            targets = { _, n ->
                when {
                    !n.visualSelected -> listOf("clip")
                    n.easing -> listOf("panel")
                    n.panel == TourFacts.TRANSFORM -> if (n.between) listOf("easing") else listOf("ruler")
                    n.panel == TourFacts.TOOLS -> listOf("tool_transform")
                    else -> listOf("back")
                }
            },
            extra = { _, n -> if (n.visualSelected && !n.easing && n.panel == TourFacts.TRANSFORM) listOf("ruler") else emptyList() },
            hint = { _, n ->
                when {
                    !n.visualSelected -> R.string.tutorial_hint_select
                    n.panel == TourFacts.TRANSFORM -> if (n.between || n.easing) null else R.string.tutorial_hint_between
                    n.panel == TourFacts.TOOLS -> null
                    else -> R.string.tutorial_hint_tools
                }
            },
            done = { s, n -> n.easing || n.eased > s.eased },
            doIt = listOf(TourAction.Ease),
            needs = { if (it.keyframes < 2) listOf(TourAction.Keyframes) else emptyList() },
        ),
        TourStep(
            "effect", R.string.tutorial_effect_title, R.string.tutorial_effect_body, 25,
            targets = { _, n ->
                when {
                    n.browsing -> emptyList()
                    !n.visualSelected -> listOf("clip")
                    n.panel == TourFacts.EFFECTS -> listOf("panel")
                    n.panel == TourFacts.TOOLS -> listOf("tool_effects")
                    else -> listOf("back")
                }
            },
            free = { _, n -> n.browsing },
            hint = { s, n ->
                when {
                    n.browsing -> null
                    !n.visualSelected -> R.string.tutorial_hint_select
                    n.effects > s.effects -> if (n.panel == TourFacts.EFFECTS) R.string.tutorial_hint_slider else R.string.tutorial_hint_effect_back
                    n.panel == TourFacts.EFFECTS || n.panel == TourFacts.TOOLS -> null
                    else -> R.string.tutorial_hint_tools
                }
            },
            stage = { s, n -> n.effects > s.effects },
            done = { s, n -> n.tune != s.tune },
            doIt = listOf(TourAction.Effect),
            needs = needsPicture,
        ),
        TourStep(
            "motion", R.string.tutorial_motion_title, R.string.tutorial_motion_body, 20,
            targets = { _, n -> if (n.visualSelected && n.panel == TourFacts.TOOLS) listOf("animate") else if (n.visualSelected) listOf("back") else listOf("clip") },
            hint = { _, n -> selectHint(n) },
            done = { s, n -> n.motions > s.motions },
            doIt = listOf(TourAction.Animation),
            needs = needsPicture,
        ),
        TourStep(
            "sound", R.string.tutorial_sound_title, R.string.tutorial_sound_body, 20,
            targets = { _, n -> pick(n, listOf("add_tab_audio", "add_audio")) },
            union = true,
            hint = { _, n -> unselectHint(n) },
            done = { s, n -> n.audio > s.audio },
            doIt = listOf(TourAction.SampleSound),
            prepare = listOf(TourAction.Unselect),
            sample = TourAction.SampleSound, sampleNote = R.string.tutorial_note_sample_sound,
        ),
        TourStep(
            "undo", R.string.tutorial_undo_title, R.string.tutorial_undo_body, 15,
            targets = { _, _ -> listOf("undo", "redo") },
            union = true,
            done = { s, n -> n.undoMark >= s.undoMark + 2 },
        ),
        TourStep(
            "safety", R.string.tutorial_safety_title, R.string.tutorial_safety_body, 12,
            targets = { _, n -> if (n.selected != null) listOf("back") else listOf("gear") },
            look = true,
            prepare = listOf(TourAction.Unselect),
        ),
        TourStep(
            "export", R.string.tutorial_export_title, R.string.tutorial_export_body, 15,
            targets = { _, n -> if (n.exporting) emptyList() else if (n.selected != null) listOf("back") else listOf("export") },
            free = { _, n -> n.exporting },
            hint = { _, n -> if (n.exporting) R.string.tutorial_hint_export_close else if (n.selected != null) R.string.tutorial_hint_unselect else null },
            stage = { _, n -> n.exporting },
            done = { _, n -> !n.exporting },
            doIt = listOf(TourAction.Export),
            prepare = listOf(TourAction.Unselect),
        ),
        TourStep("finish", R.string.tutorial_finish_title, R.string.tutorial_finish_body, 8, centred = true),
    )
}

data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2
    val centreY: Float get() = (top + bottom) / 2

    fun grown(by: Float) = Area(left - by, top - by, right + by, bottom + by)

    fun clampedTo(outer: Area) = Area(left.coerceAtLeast(outer.left), top.coerceAtLeast(outer.top), right.coerceAtMost(outer.right), bottom.coerceAtMost(outer.bottom))
}

enum class CardSide { Above, Below, Start, End, Free }

object CardPlacement {
    fun region(area: Area, hole: Area?, gap: Float, minWidth: Float, minHeight: Float): Pair<Area, CardSide> {
        if (hole == null) return area to CardSide.Free
        val candidates = listOf(
            Area(area.left, area.top, area.right, hole.top - gap) to CardSide.Above,
            Area(area.left, hole.bottom + gap, area.right, area.bottom) to CardSide.Below,
            Area(area.left, area.top, hole.left - gap, area.bottom) to CardSide.Start,
            Area(hole.right + gap, area.top, area.right, area.bottom) to CardSide.End,
        ).filter { it.first.width > 0 && it.first.height > 0 }
        if (candidates.isEmpty()) return area to CardSide.Free
        val roomy = candidates.filter { it.first.width >= minWidth && it.first.height >= minHeight }
        val vertical = roomy.filter { it.second == CardSide.Above || it.second == CardSide.Below }
        val pool = vertical.ifEmpty { roomy }.ifEmpty { return area to CardSide.Free }
        return pool.maxBy { it.first.width.coerceAtMost(minWidth * 2) * it.first.height }
    }

    fun position(region: Area, side: CardSide, hole: Area?, width: Float, height: Float, centred: Boolean): Pair<Float, Float> {
        fun clampX(x: Float) = x.coerceIn(region.left, (region.right - width).coerceAtLeast(region.left))
        fun clampY(y: Float) = y.coerceIn(region.top, (region.bottom - height).coerceAtLeast(region.top))
        val cx = hole?.centreX ?: region.centreX
        return when (side) {
            CardSide.Above -> clampX(cx - width / 2) to clampY(region.bottom - height)
            CardSide.Below -> clampX(cx - width / 2) to region.top
            CardSide.Start -> clampX(region.right - width) to clampY((hole?.centreY ?: region.centreY) - height / 2)
            CardSide.End -> region.left to clampY((hole?.centreY ?: region.centreY) - height / 2)
            CardSide.Free -> clampX(region.centreX - width / 2) to clampY(if (centred) region.centreY - height / 2 else region.bottom - height)
        }
    }
}
