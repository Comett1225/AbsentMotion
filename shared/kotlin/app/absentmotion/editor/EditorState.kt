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
import app.absentmotion.model.nestedHostTime
import app.absentmotion.model.nestedTime
import app.absentmotion.model.withValueLinksFollowing
import app.absentmotion.model.withOutlinesFollowing

enum class EditorPanel(@StringRes val title: Int) {
    Tools(0), Speed(R.string.edit_panel_time_remapping), MoveTransform(R.string.edit_panel_transform), ColorFill(R.string.edit_panel_fill_color),
    EditShape(R.string.edit_panel_shape_options), EditText(R.string.edit_panel_edit_text),
    BorderShadow(R.string.edit_panel_frame_shadow), Blending(R.string.edit_panel_opacity_blend), Effects(R.string.edit_panel_effects), Camera(R.string.edit_panel_camera_options), Model(R.string.edit_panel_model_options), Crop(R.string.layer_tool_crop),
    Edge(R.string.cutout_edge), Light(R.string.light_options), Frame(R.string.layer_tool_frame),
    Textures(R.string.model_textures), Pose(R.string.model_pose),
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

class PointAdjust(val label: String, val scene: Boolean = false, val move: (dx: Float, dy: Float, width: Float, height: Float) -> Unit)

class LayerPoints(
    val points: List<app.absentmotion.model.Pt>,
    val selected: Int,
    val pivot: Boolean = false,
    val lines: List<Pair<Int, Int>> = emptyList(),
    val guide: List<app.absentmotion.model.Pt> = emptyList(),
    val pick: (Int) -> Unit = {},
    val tap: ((app.absentmotion.model.Pt) -> Unit)? = null,
    val source: (app.absentmotion.model.Pt) -> app.absentmotion.model.Pt = { it },
    val scene: Boolean = false,
    val move: (Int, app.absentmotion.model.Pt) -> Unit,
)

class EditorState(
    val projectId: String,
    initial: Scene,
    internal val store: ProjectStore,
    val engine: EngineThread,
    val settings: AppSettings,
    val saver: ProjectSaver,
    val lookOnly: Boolean = false,
) {
    private var root by mutableStateOf(initial.withMattesFollowing())

    var groupPath by mutableStateOf<List<Long>>(emptyList())
        private set

    val project: Scene get() = root

    private var focusFor: Pair<Scene, List<Long>>? = null
    private var focusScene: Scene? = null

    val scene: Scene
        get() {
            val path = groupPath
            val whole = root
            if (path.isEmpty()) return whole
            val cached = focusFor
            if (cached != null && cached.first.node === whole.node && cached.second == path) focusScene?.let { return it }
            return (whole.focused(path) ?: whole).also { focusFor = whole to path; focusScene = it }
        }

    val inGroup: Boolean get() = groupPath.isNotEmpty()

    val groupNames: List<String>
        get() {
            var inner = root
            return groupPath.map { id -> inner.layer(id)?.also { l -> l.nested?.let { inner = it } }?.label.orEmpty() }
        }

    fun exitTo(depth: Int) { while (groupPath.size > depth.coerceAtLeast(0)) exitGroup() }

    fun enterGroup(id: Long) {
        val group = scene.layer(id)?.takeIf { it.isPlainGroup && !it.locked } ?: return
        val inner = group.nested ?: return
        endGesture()
        playing = false
        val hostMs = timeMs
        val hostFps = scene.fps
        MultiSelect.clear()
        selectedId = null
        panel = EditorPanel.Tools
        enteredMs = enteredMs.take(groupPath.size) + hostMs
        groupPath = groupPath + id
        frame = inner.frameAt(group.nestedTime(hostMs.toDouble(), hostFps, quantize = false).ms.toInt().coerceIn(0, maxOf(inner.totalTime, 0))).coerceIn(0, maxOf(scene.lastFrame, 0))
        if (!lookOnly) engine.setScene(scene)
    }

    fun exitGroup() {
        val id = groupPath.lastOrNull() ?: return
        endGesture()
        playing = false
        val innerMs = timeMs
        val entered = enteredMs.getOrNull(groupPath.size - 1)
        MultiSelect.clear()
        panel = EditorPanel.Tools
        groupPath = groupPath.dropLast(1)
        val group = scene.layer(id)
        selectedId = group?.id
        frame = scene.frameAt(group?.let { hostMsOf(it, scene.fps, innerMs.toDouble(), entered).toInt() } ?: innerMs).coerceIn(0, maxOf(scene.lastFrame, 0))
        if (!lookOnly) engine.setScene(scene)
    }

    fun leaveGroups() { while (groupPath.isNotEmpty()) exitGroup() }

    private var enteredMs: List<Int> = emptyList()

    private fun hostMsOf(group: Layer, hostFps: Int, innerMs: Double, entered: Int?): Double {
        val inner = group.nested ?: return innerMs
        if (entered != null && inner.frameAt(group.nestedTime(entered.toDouble(), hostFps, quantize = false).ms.toInt()) == inner.frameAt(innerMs.toInt())) return entered.toDouble()
        return group.nestedHostTime(innerMs, hostFps)
    }

    val premuteGain = HashMap<Long, Float>()

    var ungroupAsk by mutableStateOf(false)

    var pointsAsk by mutableStateOf<(() -> Unit)?>(null)

    val exportFrame: Int
        get() {
            if (groupPath.isEmpty()) return frame
            val chain = ArrayList<Pair<Layer, Int>>()
            var inner = root
            for (id in groupPath) { val layer = inner.layer(id)!!; chain.add(layer to inner.fps); inner = layer.nested ?: inner }
            var ms = timeMs.toDouble()
            for (i in chain.indices.reversed()) ms = hostMsOf(chain[i].first, chain[i].second, ms, enteredMs.getOrNull(i))
            return root.frameAt(ms.toInt()).coerceIn(0, maxOf(root.lastFrame, 0))
        }

    private var selection by mutableStateOf<Long?>(null)

    var selectedId: Long?
        get() = selection
        set(value) {
            if (value != null && value != selection && scene.layer(value)?.locked == true) return hint(R.string.layer_locked_hint)
            if (value != selection) { Glide.interrupt(); if (liveRecording != null) endGesture() }
            selection = value
        }

    private var frameState by mutableIntStateOf(0)

    var atEnd by mutableStateOf(false)
        private set

    var frame: Int
        get() = frameState
        set(value) {
            val old = frameState
            frameState = value
            atEnd = false
            if (value > old) recordFrames(old + 1, value)
            else if (value < old) liveRecording?.let { lap ->
                finishRecording()
                val next = lap.nextLap(scene, value)
                liveRecording = next
                if (next.active) recordFrames(value, value)
            }
        }

    val playheadFrame: Int get() = if (atEnd) frameState + 1 else frameState

    fun parkAtEnd() {
        frame = scene.lastFrame.coerceAtLeast(0)
        atEnd = true
    }

    fun stepTo(target: Int) {
        if (target > scene.lastFrame) parkAtEnd() else frame = target.coerceAtLeast(0)
    }

    private var playingState by mutableStateOf(false)
    var countInRemaining by mutableIntStateOf(0)
        private set

    var playing: Boolean
        get() = playingState
        set(value) {
            if (!value) { if (liveRecording != null) endGesture(); playingState = false; countInRemaining = 0 }
            else if (!playingState && countInRemaining == 0) {
                snapGuides = emptyList()
                if (settings.countIn > 0) countInRemaining = settings.countIn else playingState = true
            }
        }

    fun countInStep() {
        if (countInRemaining > 1) countInRemaining--
        else if (countInRemaining == 1) { countInRemaining = 0; playingState = true }
    }

    fun togglePlayback() { playing = !(playing || countInRemaining > 0) }

    var panel by mutableStateOf(EditorPanel.Tools)

    var exportOpen by mutableStateOf(false)

    var toolPage: Pair<Long?, Int> = null to 0

    var historyOpen by mutableStateOf(false)

    var presetsOpen by mutableStateOf(false)

    var toolOpen by mutableStateOf(0)

    internal var enhanceCompare by mutableStateOf<app.absentmotion.ui.editor.effects.CompareImages?>(null)
    private var browserOpen by mutableStateOf(false)
    var effectBrowserOpen: Boolean
        get() = browserOpen
        set(value) {
            if (!value && browserOpen) { effectDetail = null; effectCategory = null; effectTargets = null }
            browserOpen = value
        }
    var effectCategory by mutableStateOf<String?>(null)
    var audioFxFocus by mutableStateOf<Int?>(null)
    var effectIndex by mutableIntStateOf(-1)

    var effectDetail by mutableStateOf<String?>(null)
    var effectTargets by mutableStateOf<Set<Long>?>(null)

    var addOpen by mutableStateOf(false)

    var cameraOpen by mutableStateOf(false)

    var drawing by mutableStateOf<DrawingMode?>(null)

    var fullColor by mutableStateOf<FullColor?>(null)

    var eyedropper by mutableStateOf<((Int) -> Unit)?>(null)

    var previewSurface: Any? = null
    var previewView: app.absentmotion.engine.PreviewView? = null

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

    private val history = EditHistory({ root }, ::apply)

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

    private var shownChange by mutableStateOf<((Scene) -> Scene)?>(null)

    var previewChange: ((Scene) -> Scene)?
        get() = shownChange
        set(value) {
            if (shownChange === value) return
            shownChange = value
            if (!lookOnly) engine.setScene(value?.invoke(scene) ?: scene)
        }

    private fun show(next: Scene, xml: Lazy<String>) {
        if (lookOnly) return
        val path = groupPath
        val shown = if (path.isEmpty()) next else next.focused(path) ?: next
        val change = previewChange
        if (change == null) engine.setScene(shown, if (shown.node === next.node) xml else lazy { shown.toXml() }) else engine.setScene(change(shown))
    }

    var volumeOpen by mutableStateOf(false)

    var easingOpen by mutableStateOf(false)

    var easingHint by mutableStateOf(false)
    var easingBetween by mutableStateOf(false)

    fun hintEasing(keys: Int) {
        easingBetween = keys >= 2
        easingHint = true
    }

    var overshoot by mutableStateOf(false)

    init {
        if (!lookOnly) engine.setScene(scene)
    }

    fun edit(key: Any? = null, pivot: Long? = null, change: (Scene) -> Scene) {
        Glide.interrupt()
        val previous = liveRecording
        if (previous != null && (key != previous.key || pivot != previous.pivotId)) endGesture()
        val before = scene
        val direct = change(before)
        val changed = direct.withValueLinksFollowing(before).withMattesFollowing(before).withOutlinesFollowing(before).withFacePathsFollowing()
        if (direct.node !== before.node) lockRefusal(before, direct, groupPath.indices.any { root.focused(groupPath.take(it))?.layer(groupPath[it])?.locked == true })?.let { return hint(it) }
        if (playing) {
            val recording = liveRecording ?: LiveRecording(before, key, settings.overwriteRecording, pivot, ::normalizedPoint)
            val fresh = if (key == null) emptySet() else direct.layers.filter { before.layer(it.id)?.node?.let { old -> old !== it.node && old != it.node } == true }.map { it.id }.toSet()
            if (recording.capture(before, changed, frame, fresh)) {
                liveRecording = recording
                record(recording.render(changed, false))
                if (key == null) endGesture()
                return
            }
        }
        if (changed == before) return
        if (liveRecording != null) {
            endGesture()
            edit(key, pivot, change)
            return
        }
        history.edit(key, root.withFocused(groupPath, changed))
    }

    var typing: (() -> Unit)? = null

    fun commitTyping() {
        typing?.let { typing = null; it() }
    }

    fun endGesture() {
        finishRecording()
        val pending = drafted != null
        val ended = history.endGesture()
        if (pending || ended) {
            dropDraft()
            val now = root
            val xml = lazy { now.toXml() }
            if (pending) show(now, xml)
            if (ended) saver.commit(xml) else saver.draft(xml)
        }
        saver.flush()
    }

    private var drafted: Scene? = null
    private val sendDraft = Choreographer.FrameCallback {
        drafted?.let { next ->
            drafted = null
            val xml = lazy { next.toXml() }
            show(next, xml)
            saver.draft(xml)
        }
    }

    private fun dropDraft() {
        if (drafted == null) return
        drafted = null
        Choreographer.getInstance().removeFrameCallback(sendDraft)
    }

    fun cancelGesture(key: Any) {
        val session = liveRecording
        if (session != null && session.key == key) { liveRecording = null; history.cancelGesture(recordingKey) }
        else history.cancelGesture(key)
    }

    val gestureOpen: Boolean get() = history.gesturing

    fun amend(next: Scene) = history.amend(root.withFocused(groupPath, next.withValueLinksFollowing(scene).withMattesFollowing(scene).withOutlinesFollowing(scene)))

    internal fun forgetEdit(after: Scene?): Boolean = after != null && scene.node === after.node && history.forget(root)

    private var liveRecording: LiveRecording? = null
    private fun normalizedPoint(effect: String, name: String): Boolean = engine.effects.value.find(effect)?.params?.firstOrNull { it.id == name }?.pixels == false
    private val recordingKey = Any()

    private fun record(next: Scene) {
        val before = scene
        history.edit(recordingKey, root.withFocused(groupPath, next.withValueLinksFollowing(before).withMattesFollowing(before).withOutlinesFollowing(before)), R.string.history_record_keyframes)
    }

    private fun recordFrames(first: Int, last: Int) {
        val recording = liveRecording ?: return
        var next = scene
        for (frame in first..last) next = recording.hold(next, frame)
        record(next)
    }

    private fun finishRecording() {
        val recording = liveRecording ?: return
        liveRecording = null
        record(recording.render(scene, true))
    }

    fun editSelected(key: Any? = null, change: (Layer) -> Layer) {
        val id = selectedId ?: return
        edit(key) { it.update(id, sampleMs, change) }
    }

    val historyNotes = mutableStateListOf<HistoryNote>()
    var revealCount by mutableIntStateOf(0)
    private var historyNoteCount = 0L

    private fun note(undone: Boolean, before: Scene, after: Scene) {
        val what = summarizeEdit(before, after, { engine.effects.value.find(it)?.name }, undone)
        val text = history.lastActionNote?.let { txt(it) } ?: what.text
        showNote(HistoryNote(++historyNoteCount, txt(if (undone) R.string.history_undid else R.string.history_redid, text), spot = what.spot))
    }

    fun hint(@StringRes text: Int) {
        if (historyNotes.lastOrNull()?.let { it.faint && it.text == txt(text) } == true) return
        showNote(HistoryNote(++historyNoteCount, txt(text), faint = true))
    }

    private fun showNote(note: HistoryNote) {
        historyNotes.add(note)
        while (historyNotes.size > MAX_NOTES) historyNotes.removeAt(0)
    }

    fun undo() {
        Glide.interrupt()
        if (liveRecording != null) endGesture()
        val before = root
        history.undo()?.let { note(true, it, before) }
    }

    fun redo() {
        Glide.interrupt()
        if (liveRecording != null) endGesture()
        val before = root
        history.redo()?.let { note(false, before, it) }
    }

    private fun apply(next: Scene, save: Boolean) {
        val previous = scene
        val oldRoot = root
        val oldPath = groupPath
        root = next
        groupPath = next.openablePath(groupPath)
        val now = scene
        if (MultiSelect.active) {
            val alive = now.layers.filterNot { it.locked }.mapTo(HashSet()) { it.id }
            MultiSelect.ids = MultiSelect.ids.filterTo(LinkedHashSet()) { it in alive }
        }
        canUndo = history.canUndo
        canRedo = history.canRedo
        if (selectedId != null && now.layer(selectedId!!)?.locked != false) {
            selectedId = null
            panel = EditorPanel.Tools
            effectIndex = -1
            keyTarget = null
        }
        val kept = if (groupPath.size < oldPath.size) frameAfterLeaving(oldRoot, oldPath, previous, now, frame) else keptFrame(previous, now, frame)
        if (kept != frame) frame = kept
        if (!save) {
            if (drafted == null) Choreographer.getInstance().postFrameCallback(sendDraft)
            drafted = next
            return
        }
        dropDraft()
        val xml = lazy { next.toXml() }
        show(next, xml)
        saver.commit(xml)
    }

    private fun frameAfterLeaving(oldRoot: Scene, oldPath: List<Long>, previous: Scene, now: Scene, frame: Int): Int {
        var ms = previous.frameStart(frame).toDouble()
        for (i in oldPath.size - 1 downTo groupPath.size) {
            val host = oldRoot.focused(oldPath.take(i)) ?: return keptFrame(previous, now, frame)
            val group = host.layer(oldPath[i]) ?: return keptFrame(previous, now, frame)
            ms = hostMsOf(group, host.fps, ms, enteredMs.getOrNull(i))
        }
        return now.frameAt(ms.toInt()).coerceIn(0, maxOf(now.lastFrame, 0))
    }

    private companion object {
        const val MAX_NOTES = 2
        val ZOOM_STEPS = floatArrayOf(0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 8f, 16f, 32f, 64f)
    }
}

@StringRes
private fun lockRefusal(before: Scene, after: Scene, groupLocked: Boolean): Int? {
    if (groupLocked) return R.string.layer_locked_hint
    for (old in before.layers) {
        if (old.node["lock"] == null) continue
        val now = after.layer(old.id) ?: if (old.locked) return R.string.layer_locked_hint else continue
        if (now.node === old.node) continue
        if (now.startTime != old.startTime || now.endTime != old.endTime) return if (old.locked) R.string.layer_locked_hint else R.string.layer_timing_locked_hint
        if (old.locked && now.node.with("lock", null).with("hidden", null) != old.node.with("lock", null).with("hidden", null)) return R.string.layer_locked_hint
    }
    return null
}

internal fun keptFrame(previous: Scene, next: Scene, frame: Int): Int =
    (if (next.fps != previous.fps) next.frameAt(previous.frameStart(frame)) else frame).coerceIn(0, maxOf(next.lastFrame, 0))

internal class EditHistory(private val current: () -> Scene, private val show: (Scene, save: Boolean) -> Unit) {
    private data class Entry(val scene: Scene, @StringRes val note: Int?)
    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    var lastActionNote: Int? = null
        private set
    private var lastEditKey: Any? = null
    private var redoBeforeGesture: List<Entry> = emptyList()
    private var droppedBeforeGesture: Entry? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val gesturing: Boolean get() = lastEditKey != null

    fun edit(key: Any?, next: Scene, @StringRes note: Int? = null) {
        val scene = current()
        if (next == scene) return
        if (key == null || key != lastEditKey) {
            undoStack.addLast(Entry(scene, note))
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
        show(previous.scene, false)
    }

    fun amend(next: Scene) {
        if (next != current()) show(next, true)
    }

    fun forget(after: Scene?): Boolean {
        if (after == null || current().node !== after.node) return false
        val previous = undoStack.removeLastOrNull() ?: return false
        lastEditKey = null
        redoStack.clear()
        show(previous.scene, true)
        return true
    }

    fun undo(): Scene? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(Entry(current(), previous.note))
        lastActionNote = previous.note
        lastEditKey = null
        show(previous.scene, true)
        return previous.scene
    }

    fun redo(): Scene? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Entry(current(), next.note))
        lastActionNote = next.note
        lastEditKey = null
        show(next.scene, true)
        return next.scene
    }

    companion object {
        const val MAX_UNDO = 200
    }
}
