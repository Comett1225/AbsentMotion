#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parent.parent
REVISION = None
if len(sys.argv) > 2 and sys.argv[1] == '--revision':
    REVISION = sys.argv[2]
    del sys.argv[1:3]

def source(relative):
    path = 'app/src/main/kotlin/app/absentmotion/' + relative
    if REVISION:
        return subprocess.check_output(['git', 'show', REVISION + ':' + path], cwd=ROOT, text=True)
    return (ROOT / path).read_text()

def english(key):
    path = 'app/src/main/res/values/strings.xml'
    text = subprocess.check_output(['git', 'show', REVISION + ':' + path], cwd=ROOT, text=True) if REVISION else (ROOT / path).read_text()
    return re.search(r'<string name="' + key + r'"[^>]*>(.*?)</string>', text, re.S).group(1)

class UiContracts(unittest.TestCase):
    def test_export_rechecks_storage_on_retry_and_resume(self):
        text = source('ui/export/ExportScreen.kt')
        self.assertIn('free = freeSpace()\n        if (kind != ExportKind.Gif && storageStatus(estimate, free) is StorageStatus.NotEnough)', text)
        self.assertIn('Lifecycle.Event.ON_RESUME', text)

    def test_export_probes_missing_media_inside_the_export_job_on_io(self):
        before, job = source('ui/export/ExportScreen.kt').split('val job = scope.launch {', 1)
        self.assertNotIn('openAssetFileDescriptor', before)
        self.assertIn('val missing = withContext(Dispatchers.IO) {', job)
        self.assertIn('AudioClip.of(scene).isNotEmpty()', before)

    def test_media_info_requires_a_declaration(self):
        text = source('ui/editor/EditorMenus.kt')
        self.assertIn('if (mediaRecord != null) MenuRow(stringResource(R.string.media_info))', text)
        self.assertEqual('Media Info', english('media_info'))
        self.assertIn('if (mediaInfo && mediaRecord != null)', text)

    def test_custom_size_consumes_pending_changes_before_callbacks(self):
        text = source('ui/editor/EditorMenus.kt').split('private fun CustomSize', 1)[1]
        self.assertIn('dirty = false', text)
        self.assertLess(text.index('dirty = false'), text.index('onChange(newW, newH)'))
        self.assertIn('if (changed &&', text)
        self.assertEqual(2, text.count('dirty = true'))

    def test_player_is_owned_before_data_source_can_throw(self):
        text = source('ui/editor/AudioTab.kt').split('private class SoundPreview', 1)[1]
        self.assertIn('player = nextPlayer', text)
        self.assertLess(text.index('player = nextPlayer'), text.index('setDataSource(context, Uri.parse(next))'))
        self.assertIn('catch (_: Exception) { stop() }', text)
        self.assertIn('player?.release()', text)

    def test_template_and_preset_files_change_off_the_main_thread(self):
        tab = source('ui/home/TemplatesTab.kt')
        self.assertIn('withContext(Dispatchers.IO) { templates.delete(t.id) }', tab)
        self.assertIn('withContext(Dispatchers.IO) { presets.deleteAll() }', tab)
        self.assertIn('withContext(Dispatchers.IO) { presets.delete(p.id) }', tab)
        self.assertIn('runCatching { if (template) templates.rename(id, name) else presets.rename(id, name) }', tab)
        presets = source('ui/editor/PresetScreen.kt')
        self.assertIn('produceState<List<SavedPreset>?>(null, rev) { value = withContext(Dispatchers.IO) { store.list() } }', presets)
        self.assertIn('withContext(Dispatchers.IO) { store.delete(preset.id) }', presets)
        self.assertEqual(2, presets.count('withContext(Dispatchers.IO) { runCatching { store.rename(preset.id, title) }'))

    def test_playback_clock_restarts_when_preset_changes(self):
        text = source('ui/editor/PresetScreen.kt')
        self.assertIn('LaunchedEffect(playing, chosen, length)', text)

    def test_scrubber_uses_the_current_seek_callback(self):
        text = source('ui/editor/PresetScreen.kt')
        self.assertIn('val seekCurrent by rememberUpdatedState(onSeek)', text)
        self.assertIn('fun seek(x: Float) = seekCurrent(', text)

    def test_trailing_canvas_finger_cannot_move_a_layer(self):
        text = source('ui/editor/Preview.kt')
        self.assertIn('moved && state.selectedId != null && !(canvasGesture && fingers > 1)', text)

    def test_favorite_header_wins_when_there_are_no_recents(self):
        text = source('ui/editor/TextEditor.kt')
        self.assertIn('index == 0 && recent.isNotEmpty() -> TextIcons.Recent', text)
        self.assertIn('index == recent.size -> TextIcons.FilledStar', text)

    def test_project_sheet_scrolls_above_the_keyboard(self):
        text = source('ui/home/NewProjectSheet.kt').split('private fun SheetBody', 1)[1]
        self.assertIn('.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).verticalScroll(rememberScrollState())', text)

    def test_startup_open_and_save_stay_off_the_main_thread(self):
        text = source('MainActivity.kt')
        create = text.split('override fun onCreate', 1)[1].split('setContent {', 1)[0]
        self.assertNotIn('ShapeCatalog.load(this)', create)
        self.assertIn('lifecycleScope.launch(Dispatchers.IO) { ShapeCatalog.load(applicationContext) }', create)
        self.assertIn('withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }', create)
        load = text.split('private suspend fun loadInBackground', 1)[1].split('private fun exportFromList', 1)[0]
        self.assertIn('withContext(DiskThread.dispatcher)', load)
        self.assertIn('withContext(Dispatchers.IO)', load)
        self.assertIn('Log.i("AMStart", "onCreate took', text)
        self.assertNotIn('store.save(', source('editor/EditorState.kt'))
        self.assertIn('if (save) saver.commit(xml) else saver.draft(xml)', source('editor/EditorState.kt'))

    def test_settings_scroll_to_the_last_control(self):
        text = source('ui/home/SettingsDrawer.kt')
        self.assertIn('.statusBarsPadding().verticalScroll(rememberScrollState())', text)

    def test_theme_provider_io_leaves_the_main_thread(self):
        text = source('ui/home/SettingsDrawer.kt')
        self.assertIn('withContext(Dispatchers.IO) { readThemeText(context, uri) }', text)
        self.assertIn('catch (e: CancellationException)', text)

    def test_template_replacement_resets_choices_and_rebuilds_scene(self):
        text = source('ui/home/TemplateEditor.kt')
        self.assertIn('var choices by remember(templateId)', text)
        self.assertIn('var selected by remember(templateId)', text)
        self.assertIn('val working = remember(template, slots, choices)', text)

    def test_camera_ruler_accumulates_scaled_deltas(self):
        text = source('ui/editor/panels/CameraPanel.kt')
        self.assertIn('write(Angle, true) { it + d * Angle.perDp }', text)
        self.assertIn('writeZoom(true) { it + d * size / 200f }', text)
        self.assertIn('write(f, true) { it + d * f.perDp }', text)
        self.assertIn('{ l -> l.withValue(f.ref, formatNumber(value(l.number(f)).coerceIn(f.min, f.max))) }', text)
        self.assertNotIn('layer.number(Angle) + it', text)
        self.assertNotIn('layer.number(f) + it', text)

    def test_offscreen_video_skips_decoder_setup(self):
        text = source('ui/editor/VideoThumbnails.kt')
        self.assertIn('if (fromMs > layer.endTime || toMs < layer.startTime) return null', text)
        self.assertLess(text.index('if (fromMs > layer.endTime || toMs < layer.startTime) return null'), text.index('produceState('))

    def test_camera_selection_resets_transform_tabs(self):
        self.assertIn('var tab by remember(selected.kind)', source('ui/editor/LayerTools.kt'))

    def test_animation_button_floats_over_the_preview_in_both_layouts(self):
        tools = source('ui/editor/LayerTools.kt')
        self.assertNotIn('R.string.a11y_animate', tools)
        self.assertNotIn('AnimationSheet(', tools)
        for path in ['ui/editor/EditorScreen.kt', 'ui/editor/WideEditor.kt']:
            self.assertIn('AnimateButton(state, Modifier.align(Alignment.BottomEnd))', source(path))
        text = source('ui/editor/MotionSheets.kt').split('internal fun AnimateButton', 1)[1].split('\n}\n', 1)[0]
        self.assertIn('state.selected?.canAnimate == true', text)
        self.assertIn('Icon(PanelIcons.Animate, stringResource(R.string.a11y_animate)', text)
        self.assertIn('state.playing = false', text)

    def test_editor_menus_animate_in_and_out(self):
        root = ROOT / 'app/src/main/kotlin/app/absentmotion/ui/editor'
        paths = sorted(str(p.relative_to(ROOT / 'app/src/main/kotlin/app/absentmotion')) for p in root.rglob('*.kt'))
        for path in paths:
            self.assertIsNone(re.search(r'if \([^\n]*\) (MenuPopup|ChoiceList|ParentMenu|PasteStyleMenu|OverflowMenu)\(', source(path)), path)
        self.assertIn('Modifier.presence(scaleFrom = 0.92f', source('ui/editor/StructureUi.kt').split('internal fun ChoiceList', 1)[1])
        self.assertIn('Modifier.presence(scaleFrom = 0.85f, origin = cell.origin)', source('ui/editor/MediaTab.kt'))
        for path in ['ui/editor/EditorScreen.kt', 'ui/editor/WideEditor.kt']:
            self.assertIn('Presence(state.previewPanelOpen) { PreviewModePanel(', source(path))

    def test_every_editor_haptic_respects_the_vibration_setting(self):
        root = ROOT / 'app/src/main/kotlin/app/absentmotion/ui/editor'
        for p in sorted(root.rglob('*.kt')):
            path = str(p.relative_to(ROOT / 'app/src/main/kotlin/app/absentmotion'))
            for line in source(path).splitlines():
                if 'performHapticFeedback(' in line:
                    self.assertTrue('state.settings.vibration' in line or 'if (vibrate)' in line, path)
        self.assertIn('state.edit(MOVE_EDIT) { it.move(id, layers.lastIndex - to) }\n        onTick()', source('ui/editor/Timeline.kt'))
        self.assertIn('if (moved && value != null && value != detent) haptic(HapticFeedbackType.SegmentTick)', source('ui/editor/panels/SpeedPanel.kt'))
        self.assertIn('if (guides.any { it !in state.snapGuides }) tick(HapticFeedbackType.SegmentTick)', source('ui/editor/panels/MoveTransformPanel.kt'))

    def test_transform_tabs_put_size_above_rotate(self):
        text = source('ui/editor/LayerTools.kt')
        self.assertIn('listOf(PanelIcons.TabMove, PanelIcons.TabSize, PanelIcons.TabRotate, PanelIcons.TabTilt, PanelIcons.TabSkew)', text)
        self.assertIn('KeyTarget(PropRef.Location, "0.000000,0.000000,0.000000"),\n    KeyTarget(PropRef.Scale, "1.000000,1.000000"),\n    KeyTarget(PropRef.Rotation, "0.000000"),\n    KeyTarget(PropRef.Tilt, "0.000000,0.000000"),', text)
        self.assertIn('1 -> if (l.kind == "camera") l.withCameraFov(60f) else l.withTransform("scale", 1f, 1f)\n            2 -> l.withRotation(0f)\n            3 -> l.withTilt(0f, 0f)', source('editor/EditorActions.kt'))
        self.assertIn('val usable = i <= ROTATE_TAB || selected.kind != "camera"', text)
        self.assertIn('1 -> if (layer.kind == "camera") CameraSizeTab(state, layer) else SizeTab(state, layer)', source('ui/editor/panels/MoveTransformPanel.kt'))

    def test_every_ruler_spinner_path_moves_the_value_like_a_tape_measure(self):
        self.assertEqual(4, source('ui/editor/panels/PanelParts.kt').count('dragging(RulerMath.travel('))

    def test_rotate_and_tilt_tabs_turn_three_d_objects_on_the_preview(self):
        text = source('ui/editor/Preview.kt')
        turn = 'moved && turning != null && !(canvasGesture && fingers > 1)'
        self.assertIn(turn, text)
        self.assertLess(text.index(turn), text.index('moved && state.selectedId != null && !(canvasGesture && fingers > 1)'))
        self.assertIn('if (turn != null) TurnHook(state, turn)', source('ui/editor/panels/MoveTransformPanel.kt'))
        self.assertIn('2 -> if (turn != null) TurnTab(state, layer, turn) else RotationTab(state, layer)', source('ui/editor/panels/MoveTransformPanel.kt'))

    def test_media_search_opens_from_a_square_over_the_grid(self):
        text = source('ui/editor/MediaTab.kt')
        self.assertIn('MediaSquare(TextIcons.Search, stringResource(R.string.search_hint), searching || search.isNotEmpty()) { searching = true }', text)
        self.assertIn('BackHandler(searching) { focus.clearFocus(); searching = false }', text)
        self.assertEqual(1, text.count('SearchField('))
        self.assertLess(text.index('LazyHorizontalGrid('), text.index('SearchField('))
        self.assertIn('autoFocus = true', text.split('SearchField(', 1)[1])

    def test_deleting_preview_preset_clamps_the_position(self):
        text = source('ui/editor/PresetScreen.kt')
        self.assertIn('chosen = null; pos = pos.coerceAtMost((base.endTime - base.startTime).coerceAtLeast(1))', text)

    def test_scale_handles_guard_collapsed_axes(self):
        text = source('ui/editor/Preview.kt')
        self.assertIn('if (grabbed.getDistanceSquared() < 1e-4f) continue', text)
        self.assertLess(text.index('if (grabbed.getDistanceSquared() < 1e-4f) continue'), text.index('state.editSelected(key = "preview-handle")'))

    def test_camera_view_refreshes_selection_quad(self):
        text = source('ui/editor/Preview.kt')
        self.assertIn('LaunchedEffect(state.selectedId, scene, mediaRevision, state.cameraMode) {\n        snapshotFlow { state.timeMs }.collectLatest', text)

    def test_curve_menu_and_both_paste_paths_share_compatibility_rules(self):
        text = source('editor/EditorActions.kt')
        self.assertIn('get() = keyTarget?.ref?.let { compatibleCurve(it, copiedCurve) } != null', text)
        self.assertIn('val curve = compatibleCurve(ref, copiedCurve) ?: return', text)
        self.assertIn('val curve = compatibleCurve(ref, easing) ?: return', text)

    def test_projects_and_templates_share_rounded_aspect_matching(self):
        self.assertIn('aspectName(p.width, p.height)', source('ui/home/HomeScreen.kt'))
        self.assertIn('aspectName(scene.width, scene.height)', source('ui/home/TemplatesTab.kt'))

    def test_static_retiming_removes_the_overriding_speed_map(self):
        text = source('model/VideoTiming.kt')
        self.assertIn('n.withChild("speedMap", null).with("speed", speed.toString())', text)
        self.assertIn('current.retimedVideo(newStart, newEnd)', source('editor/VideoActions.kt'))

    def test_single_group_duplicate_remaps_children(self):
        text = source('editor/EditorActions.kt').split('fun EditorState.duplicateSelected()', 1)[1].split('fun EditorState.toggleHidden', 1)[0]
        self.assertIn('copyLayers(listOf(layer), preserveOutsideParents = true)', text)

    def test_share_labels_match_export_names(self):
        text = source('ui/export/ExportDone.kt')
        for kind, key, label in [('Video', 'export_share_video', 'Share Video'), ('Gif', 'export_share_gif', 'Share GIF'),
                                 ('Sequence', 'export_share_sequence', 'Share Image Sequence')]:
            self.assertIn('ExportKind.' + kind + ' -> R.string.' + key, text)
            self.assertEqual(label, english(key))

    def test_color_and_favorite_labels_use_consistent_spelling(self):
        for path in ['ui/editor/CaptionsSheet.kt', 'ui/editor/panels/BorderShadowPanel.kt', 'ui/editor/TextIcons.kt', 'ui/editor/effects/EffectBrowser.kt']:
            text = source(path)
            for old in ['"Colour"', '"Favourite"', '"Unfavourite"']:
                self.assertNotIn(old, text)

    def test_trim_limit_turns_the_grip_red_and_ticks_once_when_allowed(self):
        text = source('ui/editor/Timeline.kt')
        self.assertIn('val hit = layer.trimHitsSourceLimit(start, time, mediaMs)', text)
        self.assertIn('if (hit && limitEdge == null) onTick()', text)
        self.assertIn('v.onTick = { if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }', text)
        self.assertIn('drawPath(outline, if (limit) LimitColor else GripColor, alpha = appear)', text)
        self.assertIn('limitEdge = null', text.split('fun endDrag(', 1)[1].split('\n    }\n', 1)[0])
        self.assertIn('if (shown != null && shown != snapAt) onTick()', text)
        self.assertIn('if (rows > selectRows) onTick()', text)
        self.assertEqual(1, text.count('performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)'))
        self.assertIn('prefs.getBoolean("trimLimitVibration", true)', source('library/AppSettings.kt'))
        self.assertIn('SwitchRow(stringResource(R.string.settings_vibration), settings.vibration', source('ui/home/SettingsDrawer.kt'))
        self.assertEqual('Vibration', english('settings_vibration'))

    def test_holding_a_clip_moves_it_in_time_as_one_undo_step_and_a_plain_swipe_scrubs(self):
        text = source('ui/editor/Timeline.kt')
        self.assertIn('hit is Hit.Bar && view.lift(hit.id, finger, viewConfiguration.touchSlop, moves = true) -> drag = Drag.Lift', text)
        self.assertNotIn('Drag.Move', text)
        self.assertNotIn('state.selectedId && abs(moved.x)', text)
        self.assertIn('state.edit(MOVE_EDIT) { it.update(id) { _ -> it.movedClip(original, start) } }', text)
        self.assertIn('state.edit(MOVE_EDIT) { it.move(id, layers.lastIndex - to) }', text)
        self.assertIn('if (!commit || state.scene == moveBase) state.cancelGesture(MOVE_EDIT)', text)
        self.assertIn('TimelineMath.moveTarget(wanted,', text)
        self.assertIn('if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)', text)

    def test_selection_bar_pastes_copied_effects_in_one_edit_onto_visual_layers_only(self):
        bar = source('ui/editor/StructureUi.kt').split('internal fun MultiTopBar', 1)[1]
        self.assertIn('SelectionButton(PanelIcons.Effects, stringResource(R.string.layer_tool_effects)', bar)
        self.assertIn('MenuPopup(DropOrRise, { effectsMenu = false })', bar)
        self.assertIn('MenuRow(stringResource(R.string.effects_paste_all), enabled = copied.isNotEmpty()', bar)
        self.assertIn('pasteEffectsOnSelection(state, context)', bar)
        self.assertEqual('Paste All Effects', english('effects_paste_all'))
        self.assertIn('if (targets > 0 && effects.isNotEmpty()) edit { it.withEffectsPasted(ids, effects) }', source('editor/EditorActions.kt'))
        self.assertIn('it.id in ids && it.canAnimate', source('model/Effects.kt'))
        self.assertIn('EffectClipboard.copy(layer.effects, ticked)', source('ui/editor/panels/EffectsPanel.kt'))

if __name__ == '__main__':
    unittest.main()
