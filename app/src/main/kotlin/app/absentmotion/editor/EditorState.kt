package app.absentmotion.editor

import android.view.Choreographer
import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.ai.withMattesFollowing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.SnapGuide
import app.absentmotion.i18n.txt
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectSaver
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.KeyScope
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene

enum class EditorPanel(@StringRes val title: Int) {
    Tools(0), Speed(R.string.edit_panel_time_remapping), MoveTransform(R.string.edit_panel_transform), ColorFill(R.string.edit_panel_fill_color),
    EditShape(R.string.edit_panel_shape_options), EditText(R.string.edit_panel_edit_text),
    BorderShadow(R.string.edit_panel_frame_shadow), Blending(R.string.edit_panel_opacity_blend), Effects(R.string.edit_panel_effects), Camera(R.string.edit_panel_camera_options), Model(R.string.edit_panel_model_options), Crop(R.string.layer_tool_crop),
}

data class KeyTarget(val ref: PropRef, val default: String, val type: String = "float")

data class KeypadField(val label: String, val value: Float, val unit: String = "", val min: Float = -Float.MAX_VALUE, val max: Float = Float.MAX_VALUE)

data class KeypadRequest(val fields: List<KeypadField>, val active: Int, val onDone: (List<Float>) -> Unit)

class FullColor(val start: Int, val apply: (Int, drag: Boolean) -> Unit)

object Glide {
    private var job: kotlinx.coroutines.Job? = null
    private var end: (() -> Unit)? = null
    private var stepping = false

    fun start(job: kotlinx.coroutines.Job, end: () -> Unit) { stop(); this.job = job; this.end = end }

    fun step(edit: () -> Unit) { stepping = true; try { edit() } finally { stepping = false } }

    fun finished(job: kotlinx.coroutines.Job?) { if (this.job === job) { this.job = null; end = null } }

    fun stop() {
        val running = job ?: return
        val close = end
        job = null
        end = null
        running.cancel()
        close?.invoke()
    }

    internal fun interrupt() { if (!stepping) stop() }
}

class PointAdjust(val label: String, val move: (dx: Float, dy: Float) -> Unit)

class LayerPoints(
    val points: List<app.absentmotion.model.Pt>,
    val selected: Int,
    val pivot: Boolean = false,
    val lines: List<Pair<Int, Int>> = emptyList(),
    val guide: List<app.absentmotion.model.Pt> = emptyList(),
    val pick: (Int) -> Unit = {},
    val tap: ((app.absentmotion.model.Pt) -> Unit)? = null,
    val source: (app.absentmotion.model.Pt) -> app.absentmotion.model.Pt = { it },
    val move: (Int, app.absentmotion.model.Pt) -> Unit,
)

class EditorState(
    val projectId: String,
    initial: Scene,
    internal val store: ProjectStore,
    val engine: EngineThread,
    val settings: AppSettings,
    val saver: ProjectSaver,
) {
    var scene by mutableStateOf(initial.withMattesFollowing())
        private set

    private var selection by mutableStateOf<Long?>(null)

    var selectedId: Long?
        get() = selection
        set(value) { if (value != selection) Glide.interrupt(); selection = value }

    var frame by mutableIntStateOf(0)

    var playing by mutableStateOf(false)

    var panel by mutableStateOf(EditorPanel.Tools)

    var exportOpen by mutableStateOf(false)

    var historyOpen by mutableStateOf(false)

    var presetsOpen by mutableStateOf(false)

    var effectBrowserOpen by mutableStateOf(false)
    var effectCategory by mutableStateOf<String?>(null)
    var audioFxFocus by mutableStateOf<Int?>(null)
    var effectIndex by mutableIntStateOf(-1)

    var effectDetail by mutableStateOf<String?>(null)

    var addOpen by mutableStateOf(false)

    var cameraOpen by mutableStateOf(false)

    var drawing by mutableStateOf<DrawingMode?>(null)

    var fullColor by mutableStateOf<FullColor?>(null)

    var eyedropper by mutableStateOf<((Int) -> Unit)?>(null)

    var previewSurface: Any? = null

    var keypad by mutableStateOf<KeypadRequest?>(null)

    var previewPanelOpen by mutableStateOf(false)
    var transitionButtonDismissed by mutableStateOf(false)
    var pixelMode by mutableStateOf(false)
    var gridMode by mutableStateOf(false)

    val gridSpacing = 40

    var soloMode by mutableIntStateOf(0)

    private var cameraView by mutableStateOf(false)
    private var viewBeforeCamera: Pair<Float, Offset>? = null

    var cameraMode: Boolean
        get() = cameraView
        set(on) {
            if (on == cameraView) return
            if (on) {
                viewBeforeCamera = canvasZoom to canvasPan
                canvasZoom = 1f
                canvasPan = Offset.Zero
            } else viewBeforeCamera?.let { (zoom, pan) -> canvasZoom = zoom; canvasPan = pan }
            cameraTip = false
            cameraView = on
        }

    var cameraTip by mutableStateOf(false)

    var canvasZoom by mutableFloatStateOf(1f)
    var timelineZoom by mutableFloatStateOf(100f)
    var canvasPan by mutableStateOf(Offset.Zero)

    var panZoomMode by mutableStateOf(false)

    var zoomShown by mutableIntStateOf(0)

    private var viewBeforeReset: Pair<Float, Offset>? = null

    fun zoomStep(up: Boolean) {
        canvasZoom = if (up) ZOOM_STEPS.firstOrNull { it > canvasZoom } ?: ZOOM_STEPS.last()
        else ZOOM_STEPS.lastOrNull { it < canvasZoom } ?: ZOOM_STEPS.first()
        zoomShown++
    }

    fun resetCanvasView() {
        if (canvasZoom == 1f && canvasPan == Offset.Zero) viewBeforeReset?.let { (zoom, pan) -> canvasZoom = zoom; canvasPan = pan }
        else {
            viewBeforeReset = canvasZoom to canvasPan
            canvasZoom = 1f
            canvasPan = Offset.Zero
        }
        zoomShown++
    }

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    var addingMedia by mutableStateOf(false)

    private val history = EditHistory({ scene }, ::apply)

    val selected: Layer? get() = selectedId?.let { scene.layerAt(it, sampleMs) }

    val timeMs: Int get() = scene.frameStart(frame)

    val sampleMs: Int get() = scene.frameTime(frame)

    var keyTarget by mutableStateOf<KeyTarget?>(null)

    val keyScope: KeyScope?
        get() = keyTarget?.let { KeyScope(it.ref) } ?: if (panel == EditorPanel.Effects && effectIndex >= 0) KeyScope(effectIndex = effectIndex) else null

    var snapGuides by mutableStateOf<List<SnapGuide>>(emptyList())

    var pointAdjust by mutableStateOf<PointAdjust?>(null)

    var layerPoints by mutableStateOf<LayerPoints?>(null)

    var turner by mutableStateOf<((drag: Offset, twist: Float) -> Unit)?>(null)

    var cropAspect by mutableStateOf<Float?>(null)

    var previewChange: ((Scene) -> Scene)? = null
        set(value) {
            if (field === value) return
            field = value
            engine.setScene(value?.invoke(scene) ?: scene)
        }

    private fun show(next: Scene, xml: String) {
        val change = previewChange
        if (change == null) engine.setScene(next, xml) else engine.setScene(change(next))
    }

    var volumeOpen by mutableStateOf(false)

    var easingOpen by mutableStateOf(false)

    var easingHint by mutableStateOf(false)

    var overshoot by mutableStateOf(false)

    init {
        engine.setScene(scene)
    }

    fun edit(key: Any? = null, change: (Scene) -> Scene) {
        Glide.interrupt()
        val before = scene
        history.edit(key, change(before).withMattesFollowing(before))
    }

    fun endGesture() {
        val pending = drafted != null
        val ended = history.endGesture()
        if (pending || ended) {
            dropDraft()
            val xml = scene.toXml()
            if (pending) show(scene, xml)
            if (ended) saver.commit(xml) else saver.draft(xml)
        }
        saver.flush()
    }

    private var drafted: Scene? = null
    private val sendDraft = Choreographer.FrameCallback {
        drafted?.let { next ->
            drafted = null
            val xml = next.toXml()
            show(next, xml)
            saver.draft(xml)
        }
    }

    private fun dropDraft() {
        if (drafted == null) return
        drafted = null
        Choreographer.getInstance().removeFrameCallback(sendDraft)
    }

    fun cancelGesture(key: Any) = history.cancelGesture(key)

    val gestureOpen: Boolean get() = history.gesturing

    fun amend(next: Scene) = history.amend(next.withMattesFollowing(scene))

    internal fun forgetEdit(after: Scene?): Boolean = history.forget(after)

    fun editSelected(key: Any? = null, change: (Layer) -> Layer) {
        val id = selectedId ?: return
        edit(key) { it.update(id, sampleMs, change) }
    }

    val historyNotes = mutableStateListOf<HistoryNote>()
    private var historyNoteCount = 0L

    private fun note(undone: Boolean, change: Pair<Scene, Scene>) {
        val what = describeEdit(change.first, change.second)
        showNote(HistoryNote(++historyNoteCount, txt(if (undone) R.string.history_undid else R.string.history_redid, what)))
    }

    fun hint(@StringRes text: Int) = showNote(HistoryNote(++historyNoteCount, txt(text), faint = true))

    private fun showNote(note: HistoryNote) {
        historyNotes.add(note)
        while (historyNotes.size > MAX_NOTES) historyNotes.removeAt(0)
    }

    fun undo() {
        Glide.interrupt()
        val before = scene
        history.undo()?.let { note(true, it to before) }
    }

    fun redo() {
        Glide.interrupt()
        val before = scene
        history.redo()?.let { note(false, before to it) }
    }

    private fun apply(next: Scene, save: Boolean) {
        val previous = scene
        scene = next
        if (MultiSelect.active) MultiSelect.ids = MultiSelect.ids.filter { next.layer(it) != null }.toSet()
        canUndo = history.canUndo
        canRedo = history.canRedo
        if (selectedId != null && next.layer(selectedId!!) == null) selectedId = null
        frame = keptFrame(previous, next, frame)
        if (!save) {
            if (drafted == null) Choreographer.getInstance().postFrameCallback(sendDraft)
            drafted = next
            return
        }
        dropDraft()
        val xml = next.toXml()
        show(next, xml)
        saver.commit(xml)
    }

    private companion object {
        const val MAX_NOTES = 2
        val ZOOM_STEPS = floatArrayOf(0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 8f, 16f, 32f, 64f)
    }
}

internal fun keptFrame(previous: Scene, next: Scene, frame: Int): Int =
    (if (next.fps != previous.fps) next.frameAt(previous.frameStart(frame)) else frame).coerceIn(0, maxOf(next.lastFrame, 0))

internal class EditHistory(private val current: () -> Scene, private val show: (Scene, save: Boolean) -> Unit) {
    private val undoStack = ArrayDeque<Scene>()
    private val redoStack = ArrayDeque<Scene>()
    private var lastEditKey: Any? = null
    private var redoBeforeGesture: List<Scene> = emptyList()
    private var droppedBeforeGesture: Scene? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val gesturing: Boolean get() = lastEditKey != null

    fun edit(key: Any?, next: Scene) {
        val scene = current()
        if (next == scene) return
        if (key == null || key != lastEditKey) {
            undoStack.addLast(scene)
            droppedBeforeGesture = if (undoStack.size > MAX_UNDO) undoStack.removeFirst() else null
            redoBeforeGesture = redoStack.toList()
        }
        lastEditKey = key
        redoStack.clear()
        show(next, key == null)
    }

    fun endGesture(): Boolean = (lastEditKey != null).also { lastEditKey = null }

    fun cancelGesture(key: Any) {
        if (lastEditKey != key) return
        lastEditKey = null
        val previous = undoStack.removeLastOrNull() ?: return
        droppedBeforeGesture?.let { undoStack.addFirst(it) }
        droppedBeforeGesture = null
        redoStack.clear()
        redoStack.addAll(redoBeforeGesture)
        show(previous, false)
    }

    fun amend(next: Scene) {
        if (next != current()) show(next, true)
    }

    fun forget(after: Scene?): Boolean {
        if (after == null || current().node !== after.node) return false
        val previous = undoStack.removeLastOrNull() ?: return false
        lastEditKey = null
        redoStack.clear()
        show(previous, true)
        return true
    }

    fun undo(): Scene? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current())
        lastEditKey = null
        show(previous, true)
        return previous
    }

    fun redo(): Scene? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current())
        lastEditKey = null
        show(next, true)
        return next
    }

    companion object {
        const val MAX_UNDO = 200
    }
}
