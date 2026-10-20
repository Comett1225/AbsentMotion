package app.absentmotion.editor

import androidx.annotation.StringRes
import app.absentmotion.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.SnapGuide
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectSaver
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene

enum class EditorPanel(@StringRes val title: Int) {
    Tools(0), Speed(R.string.edit_panel_time_remapping), MoveTransform(R.string.edit_panel_transform), ColorFill(R.string.edit_panel_fill_color),
    EditShape(R.string.edit_panel_shape_options), EditText(R.string.edit_panel_edit_text),
    BorderShadow(R.string.edit_panel_frame_shadow), Blending(R.string.edit_panel_opacity_blend), Effects(R.string.edit_panel_effects), Camera(R.string.edit_panel_camera_options), Model(R.string.edit_panel_model_options),
}

data class KeyTarget(val ref: PropRef, val default: String, val type: String = "float")

data class KeypadField(val label: String, val value: Float)

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

class EditorState(
    val projectId: String,
    initial: Scene,
    internal val store: ProjectStore,
    val engine: EngineThread,
    val settings: AppSettings,
    val saver: ProjectSaver,
) {
    var scene by mutableStateOf(initial)
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

    private val undoStack = ArrayDeque<Scene>()
    private val redoStack = ArrayDeque<Scene>()
    private var lastEditKey: Any? = null

    val selected: Layer? get() = selectedId?.let { scene.layerAt(it, sampleMs) }

    val timeMs: Int get() = scene.frameStart(frame)

    val sampleMs: Int get() = scene.frameTime(frame)

    var keyTarget by mutableStateOf<KeyTarget?>(null)

    var snapGuides by mutableStateOf<List<SnapGuide>>(emptyList())

    var pointAdjust by mutableStateOf<PointAdjust?>(null)

    var volumeOpen by mutableStateOf(false)

    var easingOpen by mutableStateOf(false)

    var easingHint by mutableStateOf(false)

    var overshoot by mutableStateOf(false)

    init {
        engine.setScene(scene)
    }

    fun edit(key: Any? = null, change: (Scene) -> Scene) {
        Glide.interrupt()
        val next = change(scene)
        if (next == scene) return
        if (key == null || key != lastEditKey) {
            undoStack.addLast(scene)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        lastEditKey = key
        redoStack.clear()
        apply(next, save = key == null)
    }

    fun endGesture() {
        if (lastEditKey != null) saver.commit(scene.toXml())
        lastEditKey = null
        saver.flush()
    }

    fun cancelGesture(key: Any) {
        if (lastEditKey != key) return
        lastEditKey = null
        apply(undoStack.removeLastOrNull() ?: return, save = false)
    }

    fun editSelected(key: Any? = null, change: (Layer) -> Layer) {
        val id = selectedId ?: return
        edit(key) { it.update(id, sampleMs, change) }
    }

    fun undo() {
        Glide.interrupt()
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(scene)
        lastEditKey = null
        apply(previous)
    }

    fun redo() {
        Glide.interrupt()
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(scene)
        lastEditKey = null
        apply(next)
    }

    private fun apply(next: Scene, save: Boolean = true) {
        scene = next
        if (MultiSelect.active) MultiSelect.ids = MultiSelect.ids.filter { next.layer(it) != null }.toSet()
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        if (selectedId != null && next.layer(selectedId!!) == null) selectedId = null
        frame = frame.coerceIn(0, maxOf(next.lastFrame, 0))
        val xml = next.toXml()
        engine.setScene(next, xml)
        if (save) saver.commit(xml) else saver.draft(xml)
    }

    private companion object {
        const val MAX_UNDO = 200
        val ZOOM_STEPS = floatArrayOf(0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 8f, 16f, 32f, 64f)
    }
}
