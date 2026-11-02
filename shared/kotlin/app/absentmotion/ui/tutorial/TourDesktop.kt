package app.absentmotion.ui.tutorial

import app.absentmotion.R
import kotlin.math.abs

internal object DeskTourSteps {
    private fun selectHint(now: TourFacts): Int? = when {
        !now.visualSelected -> R.string.desk_tour_hint_select
        now.panel != TourFacts.TOOLS -> R.string.desk_tour_hint_tools
        else -> null
    }

    private val needsPicture: (TourFacts) -> List<TourAction> = { if (it.visual == 0) listOf(TourAction.SamplePicture) else emptyList() }

    val all: List<TourStep> = listOf(
        TourStep("welcome", R.string.tutorial_welcome_title, R.string.tutorial_welcome_body, 10, centred = true),
        TourStep("screen", R.string.tutorial_screen_title, R.string.desk_tour_screen_body, 10, targets = { _, _ -> listOf("lib_rail", "inspector", "timeline") }, look = true),
        TourStep(
            "picture", R.string.tutorial_picture_title, R.string.desk_tour_picture_body, 25,
            targets = { _, _ -> listOf("lib_tab_media", "lib_media") },
            union = true,
            done = { s, n -> n.media > s.media },
            doIt = listOf(TourAction.SamplePicture),
            sample = TourAction.SamplePicture, sampleNote = R.string.tutorial_note_sample_picture,
        ),
        TourStep(
            "gestures", R.string.desk_tour_gestures_title, R.string.desk_tour_gestures_body, 20,
            targets = { _, n -> if (n.selected == TourFacts.MEDIA) listOf("preview") else listOf("clip_media") },
            deny = listOf("transition", "animate"),
            hint = { _, n -> if (n.selected != TourFacts.MEDIA) R.string.desk_tour_hint_select else null },
            done = { s, n -> n.moves != s.moves && n.media > 0 },
            gesture = true,
            doIt = listOf(TourAction.Move),
            needs = { if (it.media == 0) listOf(TourAction.SamplePicture) else emptyList() },
        ),
        TourStep(
            "timeline", R.string.tutorial_timeline_title, R.string.desk_tour_timeline_body, 15,
            targets = { _, _ -> listOf("timeline", "playbar") },
            union = true,
            moving = true,
            done = { s, n -> abs(n.frame - s.frame) >= 3 || n.playing },
            gesture = true,
            doIt = listOf(TourAction.Scrub),
            needs = { if (it.media == 0) listOf(TourAction.SamplePicture) else emptyList() },
        ),
        TourStep(
            "split", R.string.tutorial_split_title, R.string.desk_tour_split_body, 20,
            targets = { _, n ->
                when {
                    n.selected != TourFacts.MEDIA -> listOf("clip_media")
                    n.panel != TourFacts.TOOLS -> listOf("back")
                    else -> listOf("tool_split")
                }
            },
            extra = { _, n -> if (n.selected == TourFacts.MEDIA && n.panel == TourFacts.TOOLS) listOf("ruler") else emptyList() },
            hint = { _, n -> if (n.selected != TourFacts.MEDIA) R.string.desk_tour_hint_select else if (n.panel != TourFacts.TOOLS) R.string.desk_tour_hint_tools else null },
            done = { s, n -> n.media > s.media },
            doIt = listOf(TourAction.Split),
            needs = { if (it.media == 0) listOf(TourAction.SamplePicture) else emptyList() },
        ),
        TourStep(
            "text", R.string.tutorial_text_title, R.string.desk_tour_text_body, 25,
            targets = { _, n -> if (n.textEditing) listOf("text_field", "text_done") else listOf("lib_tab_text", "lib_text") },
            union = true,
            done = { s, n -> n.texts > s.texts && !n.textEditing },
            doIt = listOf(TourAction.SampleText),
        ),
        TourStep(
            "keyframes", R.string.tutorial_keyframes_title, R.string.desk_tour_keyframes_body, 30,
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
                    !n.visualSelected -> R.string.desk_tour_hint_select
                    n.panel == TourFacts.TRANSFORM -> if (n.keyframes > s.keyframes) R.string.desk_tour_hint_second_keyframe else null
                    n.panel == TourFacts.TOOLS -> null
                    else -> R.string.desk_tour_hint_tools
                }
            },
            done = { s, n -> n.keyframes >= s.keyframes + 2 },
            gesture = true,
            doIt = listOf(TourAction.Keyframes),
            needs = needsPicture,
        ),
        TourStep(
            "easing", R.string.tutorial_easing_title, R.string.desk_tour_easing_body, 15,
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
                    !n.visualSelected -> R.string.desk_tour_hint_select
                    n.panel == TourFacts.TRANSFORM -> if (n.between || n.easing) null else R.string.desk_tour_hint_between
                    n.panel == TourFacts.TOOLS -> null
                    else -> R.string.desk_tour_hint_tools
                }
            },
            done = { s, n -> n.easing || n.eased > s.eased },
            doIt = listOf(TourAction.Ease),
            needs = { if (it.keyframes < 2) listOf(TourAction.Keyframes) else emptyList() },
        ),
        TourStep(
            "effect", R.string.tutorial_effect_title, R.string.desk_tour_effect_body, 25,
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
                    !n.visualSelected -> R.string.desk_tour_hint_select
                    n.effects > s.effects -> if (n.panel == TourFacts.EFFECTS) R.string.desk_tour_hint_slider else R.string.desk_tour_hint_effect_back
                    n.panel == TourFacts.EFFECTS || n.panel == TourFacts.TOOLS -> null
                    else -> R.string.desk_tour_hint_tools
                }
            },
            stage = { s, n -> n.effects > s.effects },
            done = { s, n -> n.tune != s.tune },
            gesture = true,
            doIt = listOf(TourAction.Effect),
            needs = needsPicture,
        ),
        TourStep(
            "motion", R.string.tutorial_motion_title, R.string.desk_tour_motion_body, 20,
            targets = { _, n -> if (n.visualSelected && n.panel == TourFacts.TOOLS) listOf("animate") else if (n.visualSelected) listOf("back") else listOf("clip") },
            hint = { _, n -> selectHint(n) },
            done = { s, n -> n.motions > s.motions },
            doIt = listOf(TourAction.Animation),
            needs = needsPicture,
        ),
        TourStep(
            "sound", R.string.tutorial_sound_title, R.string.desk_tour_sound_body, 20,
            targets = { _, _ -> listOf("lib_tab_audio", "lib_audio") },
            union = true,
            done = { s, n -> n.audio > s.audio },
            doIt = listOf(TourAction.SampleSound),
            sample = TourAction.SampleSound, sampleNote = R.string.tutorial_note_sample_sound,
        ),
        TourStep(
            "undo", R.string.tutorial_undo_title, R.string.desk_tour_undo_body, 15,
            targets = { _, _ -> listOf("undo", "redo") },
            union = true,
            done = { s, n -> n.undoMark >= s.undoMark + 2 },
        ),
        TourStep("safety", R.string.tutorial_safety_title, R.string.desk_tour_safety_body, 12, targets = { _, _ -> listOf("gear") }, look = true),
        TourStep(
            "export", R.string.tutorial_export_title, R.string.desk_tour_export_body, 15,
            targets = { _, n -> if (n.exporting) emptyList() else listOf("export") },
            free = { _, n -> n.exporting },
            hint = { _, n -> if (n.exporting) R.string.desk_tour_hint_export_close else null },
            stage = { _, n -> n.exporting },
            done = { _, n -> !n.exporting },
            doIt = listOf(TourAction.Export),
            prepare = listOf(TourAction.Unselect),
        ),
        TourStep("finish", R.string.tutorial_finish_title, R.string.tutorial_finish_body, 8, centred = true),
    )
}
