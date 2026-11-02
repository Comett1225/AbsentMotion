package app.absentmotion.desk

import android.net.Uri
import android.content.mime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.split
import app.absentmotion.ui.editor.MediaPicks
import app.absentmotion.ui.home.SettingsPage
import app.absentmotion.platform.linkMediaFolder
import kotlinx.coroutines.launch

@Composable
fun FrameWindowScope.DeskMenus(app: DeskAppState) {
    val scope = rememberCoroutineScope()
    val mac = DeskOs.mac
    fun key(k: Key, shift: Boolean = false) = KeyShortcut(k, meta = mac, ctrl = !mac, shift = shift)
    val editor = app.editor
    MenuBar {
        Menu("File") {
            Item("New Project…", enabled = editor == null, shortcut = key(Key.N)) { app.newProjectTick++ }
            Item("Settings…", shortcut = key(Key.Comma)) { app.openSettings() }
            Separator()
            Item("Import Media…", enabled = editor != null) {
                val files = DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile)
                val sounds = files.filter { android.content.Desk.mime(it.toString())?.startsWith("audio/") == true }
                if ((files - sounds.toSet()).isNotEmpty()) MediaPicks.addPictures(files - sounds.toSet())
                if (sounds.isNotEmpty()) MediaPicks.addSounds(sounds)
            }
            Item("Import Project…", enabled = editor == null) {
                val files = DeskFiles.chooseFiles(media = false, multiple = true).map(Uri::fromFile)
                if (files.isNotEmpty()) scope.launch { app.importFiles(files) }
            }
            Item("Link Media Folder…") { linkMediaFolder() }
            val linked = DeskLibrary.folders.also { DeskLibrary.revision.intValue }
            if (linked.isNotEmpty()) Menu("Unlink Folder") { linked.forEach { folder -> Item(folder.name.ifEmpty { folder.path }) { DeskLibrary.unlink(folder) } } }
            Separator()
            Item("Export…", enabled = editor != null, shortcut = key(Key.E)) { editor?.let { it.playing = false; it.exportOpen = true } }
            Item("Close Project", enabled = editor != null, shortcut = key(Key.W)) { scope.launch { app.closeEditor() } }
        }
        Menu("Edit") {
            Item("Undo", enabled = editor?.canUndo == true) { editor?.undo() }
            Item("Redo", enabled = editor?.canRedo == true) { editor?.redo() }
            Separator()
            Item("Cut Layer", enabled = editor?.chosenLayers?.isNotEmpty() == true) { editor?.let { it.copySelected(); if (MultiSelect.active) it.deleteChosen() else it.deleteSelected() } }
            Item("Copy Layer", enabled = editor?.chosenLayers?.isNotEmpty() == true) { editor?.copySelected() }
            Item("Paste Layer", enabled = editor != null) { editor?.pasteLayer() }
            Item("Duplicate", enabled = editor?.selectedId != null || MultiSelect.active) { editor?.let { if (MultiSelect.active) it.duplicateChosen() else it.duplicateSelected() } }
            Item("Split at Playhead", enabled = editor?.selectedId != null || MultiSelect.active) { editor?.split() }
            Item("Delete", enabled = editor?.selectedId != null || MultiSelect.active) { editor?.let { if (MultiSelect.active) it.deleteChosen() else it.deleteSelected() } }
        }
        Menu("View") {
            Item("Zoom In", enabled = DeskScale.percent < DeskScale.MAX, shortcut = key(Key.Equals)) { DeskScale.zoomIn() }
            Item("Zoom Out", enabled = DeskScale.percent > DeskScale.MIN, shortcut = key(Key.Minus)) { DeskScale.zoomOut() }
            Item("Actual Size", enabled = DeskScale.percent != 100, shortcut = key(Key.Zero)) { DeskScale.reset() }
        }
        Menu("Playback") {
            Item(if (editor?.playing == true) "Pause" else "Play", enabled = editor != null) { editor?.let { it.playing = !it.playing } }
            Item("Go to Start", enabled = editor != null) { editor?.let { it.playing = false; it.frame = 0 } }
            Item("Go to End", enabled = editor != null) { editor?.let { it.playing = false; it.frame = it.scene.lastFrame } }
        }
        Menu("Help") {
            Item("AbsentMotion Help") { app.openSettings(SettingsPage.Help) }
            Item("About AbsentMotion") { app.openSettings(SettingsPage.About) }
            Item("Crash Logs") { app.openSettings(SettingsPage.Crash) }
        }
    }
}
