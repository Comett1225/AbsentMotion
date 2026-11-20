package app.absentmotion.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.addKeyframe
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.copyKeyframes
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.selectAll
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteKeyframes
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.split
import app.absentmotion.editor.toggleBookmark
import app.absentmotion.i18n.AppText
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.ui.editor.DeskTimelineControls
import app.absentmotion.ui.editor.TimelineMath
import org.json.JSONArray
import org.json.JSONObject

data class MenuKey(val key: Key, val command: Boolean = true, val alt: Boolean = false, val shift: Boolean = false)

val ModifierKeys = setOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight, Key.CapsLock, Key.Function)

internal fun shortcutText(shortcut: MenuKey, mac: Boolean): String {
    val key = when (shortcut.key) {
        Key.Comma -> ","
        Key.Period -> "."
        Key.Equals -> "="
        Key.Minus -> "-"
        Key.Plus -> "+"
        Key.DirectionLeft -> "←"
        Key.DirectionRight -> "→"
        Key.DirectionUp -> "↑"
        Key.DirectionDown -> "↓"
        else -> ShortcutPlatform.keyName(shortcut.key)
    }
    return if (mac) (if (shortcut.alt) "⌥" else "") + (if (shortcut.shift) "⇧" else "") + (if (shortcut.command) "⌘" else "") + key
    else (if (shortcut.command) "Ctrl+" else "") + (if (shortcut.alt) "Alt+" else "") + (if (shortcut.shift) "Shift+" else "") + key
}

private fun cmd(key: Key, shift: Boolean = false) = MenuKey(key, command = true, shift = shift)
private fun plain(key: Key, shift: Boolean = false, alt: Boolean = false) = MenuKey(key, command = false, alt = alt, shift = shift)

enum class Shortcut(val group: Int, val label: Int, val defaults: List<MenuKey>, val repeats: Boolean = false, val global: Boolean = false) {
    NewProject(R.string.desk_menu_file, R.string.desk_menu_new_project, listOf(cmd(Key.N)), global = true),
    Settings(R.string.desk_menu_file, R.string.settings, listOf(cmd(Key.Comma)), global = true),
    Export(R.string.desk_menu_file, R.string.desk_export, listOf(cmd(Key.E))),
    CloseProject(R.string.desk_menu_file, R.string.desk_menu_close_project, listOf(cmd(Key.W))),
    Quit(R.string.desk_menu_file, R.string.desk_export_quit, listOf(cmd(Key.Q)), global = true),
    Undo(R.string.history_edit, R.string.draw_undo, listOf(cmd(Key.Z)), repeats = true),
    Redo(R.string.history_edit, R.string.draw_redo, listOf(cmd(Key.Z, shift = true), cmd(Key.Y)), repeats = true),
    Cut(R.string.history_edit, R.string.desk_menu_cut_layer, listOf(cmd(Key.X))),
    Copy(R.string.history_edit, R.string.desk_menu_copy_layer, listOf(cmd(Key.C))),
    Paste(R.string.history_edit, R.string.desk_menu_paste_layer, listOf(cmd(Key.V))),
    Duplicate(R.string.history_edit, R.string.home_duplicate, listOf(cmd(Key.D))),
    Split(R.string.history_edit, R.string.timeline_split, listOf(cmd(Key.B))),
    Delete(R.string.history_edit, R.string.delete, listOf(plain(Key.Delete), plain(Key.Backspace), plain(Key.Delete, shift = true), plain(Key.Backspace, shift = true))),
    SelectAll(R.string.history_edit, R.string.menu_select_all_layers, listOf(cmd(Key.A))),
    CopyKeyframes(R.string.history_edit, R.string.desk_key_copy_keyframes, listOf(cmd(Key.C, shift = true))),
    PasteKeyframes(R.string.history_edit, R.string.desk_key_paste_keyframes, listOf(cmd(Key.V, shift = true))),
    AddKeyframe(R.string.history_edit, R.string.a11y_add_keyframe, listOf(plain(Key.K, alt = true))),
    AddBookmark(R.string.history_edit, R.string.timeline_add_bookmark, listOf(plain(Key.M))),
    NudgeEarlier(R.string.history_edit, R.string.desk_key_nudge_earlier, listOf(plain(Key.Comma)), repeats = true),
    NudgeLater(R.string.history_edit, R.string.desk_key_nudge_later, listOf(plain(Key.Period)), repeats = true),
    NudgeEarlier10(R.string.history_edit, R.string.desk_key_nudge_earlier_10, listOf(plain(Key.Comma, shift = true)), repeats = true),
    NudgeLater10(R.string.history_edit, R.string.desk_key_nudge_later_10, listOf(plain(Key.Period, shift = true)), repeats = true),
    PlayPause(R.string.desk_menu_playback, R.string.play_play, listOf(plain(Key.Spacebar), plain(Key.Spacebar, shift = true))),
    PreviousFrame(R.string.desk_menu_playback, R.string.desk_previous_frame, listOf(plain(Key.DirectionLeft)), repeats = true),
    NextFrame(R.string.desk_menu_playback, R.string.desk_next_frame, listOf(plain(Key.DirectionRight)), repeats = true),
    Back10(R.string.desk_menu_playback, R.string.desk_key_back_10, listOf(plain(Key.DirectionLeft, shift = true)), repeats = true),
    Forward10(R.string.desk_menu_playback, R.string.desk_key_forward_10, listOf(plain(Key.DirectionRight, shift = true)), repeats = true),
    GoStart(R.string.desk_menu_playback, R.string.desk_menu_go_start, listOf(plain(Key.MoveHome), cmd(Key.MoveHome)), repeats = true),
    GoEnd(R.string.desk_menu_playback, R.string.desk_menu_go_end, listOf(plain(Key.MoveEnd), cmd(Key.MoveEnd)), repeats = true),
    PreviousKeyframe(R.string.desk_menu_playback, R.string.desk_key_previous_keyframe, listOf(plain(Key.J)), repeats = true),
    NextKeyframe(R.string.desk_menu_playback, R.string.desk_key_next_keyframe, listOf(plain(Key.K)), repeats = true),
    PreviousBookmark(R.string.desk_menu_playback, R.string.desk_menu_previous_bookmark, listOf(plain(Key.DirectionLeft, alt = true), plain(Key.DirectionLeft, shift = true, alt = true)), repeats = true),
    NextBookmark(R.string.desk_menu_playback, R.string.desk_menu_next_bookmark, listOf(plain(Key.DirectionRight, alt = true), plain(Key.DirectionRight, shift = true, alt = true)), repeats = true),
    TimelineZoomIn(R.string.desk_menu_view, R.string.desk_key_timeline_zoom_in, listOf(plain(Key.Equals), plain(Key.Equals, shift = true), plain(Key.Plus), plain(Key.NumPadAdd)), repeats = true),
    TimelineZoomOut(R.string.desk_menu_view, R.string.desk_key_timeline_zoom_out, listOf(plain(Key.Minus), plain(Key.Minus, shift = true), plain(Key.NumPadSubtract)), repeats = true),
    TimelineFit(R.string.desk_menu_view, R.string.desk_key_timeline_fit, listOf(plain(Key.Z, shift = true))),
    Library(R.string.desk_menu_view, R.string.desk_library, listOf(cmd(Key.One))),
    Inspector(R.string.desk_menu_view, R.string.desk_inspector, listOf(cmd(Key.Two))),
    ZoomIn(R.string.desk_menu_view, R.string.desk_menu_zoom_in, listOf(cmd(Key.Equals), cmd(Key.Equals, shift = true), cmd(Key.Plus), cmd(Key.NumPadAdd)), repeats = true, global = true),
    ZoomOut(R.string.desk_menu_view, R.string.desk_menu_zoom_out, listOf(cmd(Key.Minus), cmd(Key.Minus, shift = true), cmd(Key.NumPadSubtract)), repeats = true, global = true),
    ActualSize(R.string.desk_menu_view, R.string.desk_menu_actual_size, listOf(cmd(Key.Zero), cmd(Key.NumPad0)), global = true);

    val text: String get() = if (this == PlayPause) AppText.get(R.string.play_play) + " / " + AppText.get(R.string.play_pause) else AppText.get(label)
}

object DeskShortcuts {
    private const val PREF = "shortcuts"
    private val prefs get() = ShortcutPlatform.prefs

    var custom by mutableStateOf(decode(prefs?.getString(PREF, null)))
        private set

    var viewer: ((KeyEvent) -> Boolean)? = null

    val shown: List<Shortcut> get() = Shortcut.entries.filter { it != Shortcut.Quit || !ShortcutPlatform.mac }

    fun reload() { custom = decode(prefs?.getString(PREF, null)); publish() }

    fun keys(action: Shortcut): List<MenuKey> = custom[action] ?: action.defaults

    fun chord(event: KeyEvent): MenuKey = MenuKey(event.key, command = ShortcutPlatform.isCommand(event), alt = event.isAltPressed, shift = event.isShiftPressed)

    fun bindable(event: KeyEvent): Boolean = ShortcutPlatform.bindable(event)

    fun find(chord: MenuKey, among: (Shortcut) -> Boolean = { true }): Shortcut? = Shortcut.entries.firstOrNull { among(it) && chord in keys(it) }

    fun conflict(action: Shortcut, chord: MenuKey): Shortcut? = find(chord) { it != action }

    fun conflict(action: Shortcut, chords: List<MenuKey>): Shortcut? = chords.firstNotNullOfOrNull { conflict(action, it) }

    fun assign(action: Shortcut, chords: List<MenuKey>) = save(taken(chords, action) + (action to chords))

    fun bind(action: Shortcut, chord: MenuKey) = assign(action, listOf(chord))

    fun reset(action: Shortcut) = assign(action, action.defaults)

    fun clear(action: Shortcut) = assign(action, emptyList())

    fun resetAll() = save(emptyMap())

    private fun taken(chords: List<MenuKey>, by: Shortcut): Map<Shortcut, List<MenuKey>> =
        custom + Shortcut.entries.filter { it != by && keys(it).any(chords::contains) }.associateWith { keys(it) - chords.toSet() }

    private fun save(next: Map<Shortcut, List<MenuKey>>) {
        custom = next.filter { (action, keys) -> keys != action.defaults }
        prefs?.edit()?.putString(PREF, if (custom.isEmpty()) null else encode(custom))?.apply()
        publish()
    }

    fun text(action: Shortcut): String = keys(action).firstOrNull()?.let { shortcutText(it, ShortcutPlatform.mac) } ?: ""

    fun defaultText(action: Shortcut): String = action.defaults.firstOrNull()?.let { shortcutText(it, ShortcutPlatform.mac) } ?: ""

    fun run(state: EditorState, action: Shortcut, close: () -> Unit): Boolean {
        val picked = MultiSelect.active || state.selectedId != null
        fun step(by: Int) { state.playing = false; state.stepTo(state.playheadFrame + by) }
        fun nudge(by: Int) {
            val layers = state.chosenLayers
            val firstLayer = layers.firstOrNull() ?: return
            val earliest = layers.minOf { state.scene.firstShownFrame(it.startTime) }
            val delta = by.coerceAtLeast(-earliest)
            val frame = state.scene.firstShownFrame(firstLayer.startTime) + delta
            if (delta != 0) state.edit { TimelineMath.movedClips(it, firstLayer, layers.drop(1), frame) }
        }
        fun delete() { if (MultiSelect.active) state.deleteChosen() else state.deleteSelected() }
        when (action) {
            Shortcut.PlayPause -> state.togglePlayback()
            Shortcut.PreviousFrame -> step(-1)
            Shortcut.NextFrame -> step(1)
            Shortcut.Back10 -> step(-10)
            Shortcut.Forward10 -> step(10)
            Shortcut.GoStart -> { state.playing = false; state.frame = 0 }
            Shortcut.GoEnd -> { state.playing = false; state.parkAtEnd() }
            Shortcut.PreviousBookmark -> DeskJump.previous(state)
            Shortcut.NextBookmark -> DeskJump.next(state)
            Shortcut.PreviousKeyframe -> DeskJump.previousKey(state)
            Shortcut.NextKeyframe -> DeskJump.nextKey(state)
            Shortcut.NudgeEarlier -> nudge(-1)
            Shortcut.NudgeLater -> nudge(1)
            Shortcut.NudgeEarlier10 -> nudge(-10)
            Shortcut.NudgeLater10 -> nudge(10)
            Shortcut.SelectAll -> state.selectAll()
            Shortcut.TimelineFit -> DeskTimelineControls.fit?.invoke() ?: return false
            Shortcut.TimelineZoomIn -> DeskTimelineControls.zoom?.invoke(1.25f) ?: return false
            Shortcut.TimelineZoomOut -> DeskTimelineControls.zoom?.invoke(0.8f) ?: return false
            Shortcut.Undo -> if (state.canUndo) state.undo()
            Shortcut.Redo -> if (state.canRedo) state.redo()
            Shortcut.Delete -> DeskDrop.removeHovered?.invoke() ?: if (picked) delete() else return false
            Shortcut.Duplicate -> if (picked) { if (MultiSelect.active) state.duplicateChosen() else state.duplicateSelected() } else return false
            Shortcut.Copy -> if (state.chosenLayers.isNotEmpty()) state.copySelected() else return false
            Shortcut.Cut -> if (state.chosenLayers.isNotEmpty()) { state.copySelected(); delete() } else return false
            Shortcut.Paste -> state.pasteLayer()
            Shortcut.Split -> if (picked) state.split() else return false
            Shortcut.CopyKeyframes -> return state.copyKeyframes()
            Shortcut.PasteKeyframes -> return state.pasteKeyframes()
            Shortcut.AddKeyframe -> return state.addKeyframe()
            Shortcut.AddBookmark -> if (state.scene.bookmarks.none { state.scene.frameAt(it) == state.frame }) state.toggleBookmark()
            Shortcut.Library, Shortcut.Inspector -> DeskDrop.panelKey?.invoke(action == Shortcut.Library) ?: return false
            Shortcut.Export -> { state.playing = false; state.exportOpen = true }
            Shortcut.CloseProject -> close()
            else -> return false
        }
        return true
    }

    fun publish() {
        DeskDrop.keyNames.clear()
        Shortcut.entries.forEach { DeskDrop.keyNames[it.name] = text(it) }
        DeskDrop.panelChord = { event ->
            when (find(chord(event))) {
                Shortcut.Library -> true
                Shortcut.Inspector -> false
                else -> null
            }
        }
    }

    internal fun encode(map: Map<Shortcut, List<MenuKey>>): String = JSONObject().also { json ->
        map.forEach { (action, keys) ->
            json.put(action.name, JSONArray(keys.map { k -> "${k.key.keyCode}:" + (if (k.command) "c" else "") + (if (k.alt) "a" else "") + (if (k.shift) "s" else "") }))
        }
    }.toString()

    internal fun decode(text: String?): Map<Shortcut, List<MenuKey>> = runCatching {
        val json = JSONObject(text ?: return emptyMap())
        Shortcut.entries.filter { json.has(it.name) }.associateWith { action ->
            val list = json.getJSONArray(action.name)
            (0 until list.length()).mapNotNull { i ->
                val (code, mods) = list.getString(i).split(':').let { it[0] to it.getOrElse(1) { "" } }
                code.toLongOrNull()?.let { MenuKey(Key(it), command = 'c' in mods, alt = 'a' in mods, shift = 's' in mods) }
            }
        }
    }.getOrDefault(emptyMap())
}
