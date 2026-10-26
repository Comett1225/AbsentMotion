package app.absentmotion.ui.tutorial

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.editor.addText
import app.absentmotion.editor.clearSelection
import app.absentmotion.editor.imageLayer
import app.absentmotion.editor.split
import app.absentmotion.media.imageDeclaration
import app.absentmotion.model.Easing
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.MotionSlot
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.content
import app.absentmotion.model.effects
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.withAnimation
import app.absentmotion.model.withContent
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withTextSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun sceneFacts(scene: Scene): TourFacts {
    var media = 0
    var mediaMs = 0
    var texts = 0
    var audio = 0
    var moves = 17
    var effects = 0
    var tune = 17
    var motions = 0
    val keys = IntArray(2)
    for (layer in scene.layers) {
        when (kindOf(layer)) {
            TourFacts.AUDIO -> audio++
            TourFacts.MEDIA -> { media++; mediaMs += layer.endTime - layer.startTime }
            TourFacts.TEXT -> if (layer.content.isNotBlank()) texts++
        }
        if (layer.kind != "audio") moves = 31 * moves + (layer.node.child("transform")?.hashCode() ?: 0)
        if (MotionSlot.entries.any { layer.node[it.attr] != null }) motions++
        val own = layer.effects.filter { it["gen"] == null }
        effects += own.size
        tune = 31 * tune + own.hashCode()
        countKeys(layer.node, keys)
    }
    return TourFacts(media = media, mediaMs = mediaMs, texts = texts, audio = audio, moves = moves, keyframes = keys[0], eased = keys[1], effects = effects, tune = tune, motions = motions)
}

private fun countKeys(node: XmlNode, out: IntArray) {
    for (child in node.children) when {
        child.name == "kf" -> if (child["gen"] == null) {
            out[0]++
            if (child["e"] != null) out[1]++
        }
        child.name == "scene" || child.name == "effect" && child["gen"] != null -> Unit
        else -> countKeys(child, out)
    }
}

internal fun kindOf(layer: Layer): String = when {
    layer.kind == "audio" -> TourFacts.AUDIO
    layer.mediaFill != null -> TourFacts.MEDIA
    layer.kind == "text" -> TourFacts.TEXT
    else -> TourFacts.OTHER
}

internal fun tourFacts(state: EditorState, editing: Boolean): TourFacts = sceneFacts(state.scene).copy(
    editing = editing,
    selected = state.selected?.let(::kindOf),
    panel = state.panel.name,
    adding = state.addOpen,
    frame = state.frame,
    playing = state.playing,
    undoMark = state.historyNotes.lastOrNull()?.id ?: 0L,
    keypad = state.keypad != null,
    exporting = state.exportOpen,
    browsing = state.effectBrowserOpen,
    easing = state.easingOpen,
    between = state.selected?.let { layer -> TourHands.EASED.any { layer.easing(it) != null } } == true,
)

internal class TourHands(private val context: Context, private val state: EditorState) {
    suspend fun perform(action: TourAction) {
        when (action) {
            TourAction.Pause -> state.playing = false
            TourAction.Unselect -> state.clearSelection()
            TourAction.SamplePicture -> samplePictures()
            TourAction.SampleText -> sampleText()
            TourAction.SampleSound -> sampleSound()
            TourAction.Move -> move()
            TourAction.Scrub -> scrub()
            TourAction.Split -> split()
            TourAction.Keyframes -> keyframes()
            TourAction.Ease -> ease()
            TourAction.Effect -> effect()
            TourAction.Animation -> animation()
            TourAction.Export -> { state.playing = false; state.clearSelection(); state.exportOpen = true }
        }
    }

    private suspend fun copied(asset: String, name: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = state.store.mediaDir(state.projectId).apply { mkdirs() }
            val file = File(dir, name)
            val bytes = context.assets.open("tutorial/$asset").use { it.readBytes() }
            if (!file.isFile || file.length() != bytes.size.toLong()) {
                val temp = File(dir, "$name.part")
                temp.writeBytes(bytes)
                if (!temp.renameTo(file)) { temp.delete(); error("cannot keep $name") }
            }
            file
        }.onFailure { app.absentmotion.platform.Log.w("AMTutorial", "cannot copy the sample $asset", it) }.getOrNull()
    }

    private fun titled(media: MediaDeclaration, title: Int) = MediaDeclaration(media.node.with("title", context.getString(title)))

    private suspend fun picture(asset: String, name: String, title: Int): MediaDeclaration? =
        copied(asset, name)?.let { imageDeclaration(context, Uri.fromFile(it)) }?.let { titled(it, title) }

    private suspend fun samplePictures() {
        val first = picture("picture.jpg", "tutorial-sample-picture.jpg", R.string.tutorial_sample_picture) ?: return
        val second = picture("picture2.jpg", "tutorial-sample-picture-2.jpg", R.string.tutorial_sample_picture_two)
        val seconds = state.settings.defaultLayerDuration
        val scene = state.scene
        val a = scene.imageLayer(first, 0, seconds) ?: return
        val b = second?.let { scene.imageLayer(it, scene.frameAt(a.endTime) + 1, seconds) }
        state.edit { s -> s.add(a, listOf(first)).let { next -> if (b != null) next.add(b, listOfNotNull(second)) else next } }
        state.addOpen = false
        state.selectedId = a.id
        state.panel = EditorPanel.Tools
        state.frame = 0
    }

    private fun sampleText() {
        state.clearSelection()
        state.addText()
        state.editSelected { it.withContent(context.getString(R.string.tutorial_sample_text)).withTextSize(48f).withFill("color", WHITE) }
        state.panel = EditorPanel.Tools
    }

    private suspend fun sampleSound() {
        val media = copied("sound.m4a", "tutorial-sample-sound.m4a")?.let { audioDeclaration(context, Uri.fromFile(it)) }?.let { titled(it, R.string.tutorial_sample_sound) } ?: return
        state.clearSelection()
        state.frame = 0
        state.addAudio(media)
    }

    private fun visual(): Layer? = state.selected?.takeIf { kindOf(it) != TourFacts.AUDIO }
        ?: state.scene.layers.firstOrNull { kindOf(it) == TourFacts.MEDIA } ?: state.scene.layers.firstOrNull { kindOf(it) == TourFacts.TEXT }

    private suspend fun visualOrSample(): Layer? = visual() ?: run { samplePictures(); visual() }

    private fun focus(layer: Layer, panel: EditorPanel) {
        state.addOpen = false
        state.selectedId = layer.id
        state.panel = panel
    }

    private suspend fun move() {
        val layer = state.selected?.takeIf { kindOf(it) == TourFacts.MEDIA } ?: state.scene.layers.firstOrNull { kindOf(it) == TourFacts.MEDIA } ?: run { samplePictures(); visual() } ?: return
        state.edit { it.update(layer.id, state.sampleMs) { l -> l.withTransform("scale", l.scale[0] * 0.8f, l.scale[1] * 0.8f).withRotation(l.rotation + 8f) } }
        focus(layer, EditorPanel.Tools)
    }

    private fun scrub() {
        state.playing = false
        val last = state.scene.lastFrame
        val ahead = state.frame + state.scene.fps
        state.frame = if (ahead <= last) ahead else last / 2
    }

    private suspend fun split() {
        val layer = state.scene.layers.firstOrNull { kindOf(it) == TourFacts.MEDIA } ?: run { samplePictures(); state.scene.layers.firstOrNull { kindOf(it) == TourFacts.MEDIA } } ?: return
        focus(layer, EditorPanel.Tools)
        state.frame = state.scene.frameAt((layer.startTime + layer.endTime) / 2)
        state.split()
    }

    private suspend fun keyframes() {
        val layer = visualOrSample() ?: return
        val width = state.scene.width * 0.18f
        state.edit { scene ->
            scene.update(layer.id) { l ->
                val at = l.location
                val element = l.element(PropRef.Location) ?: XmlNode("location")
                val kept = element.keyframes.filter { it.gen != null }
                l.withElement(PropRef.Location, element.withKeyframes(kept + Keyframe(0f, formatFloats(floatArrayOf(at[0] - width, at[1], at[2]))) +
                    Keyframe(1f, formatFloats(floatArrayOf(at[0] + width, at[1], at[2])))))
            }
        }
        focus(layer, EditorPanel.MoveTransform)
        state.frame = state.scene.frameAt((layer.startTime + layer.endTime) / 2)
    }

    private suspend fun ease() {
        val keyed = state.scene.layers.firstOrNull { l -> EASED.any { ref -> l.keyframes(ref).count { it.gen == null } >= 2 } } ?: run { keyframes(); visual() } ?: return
        val ref = EASED.firstOrNull { ref -> keyed.keyframes(ref).count { it.gen == null } >= 2 } ?: return
        state.edit { it.update(keyed.id) { l -> l.withEasingEverywhere(ref, EASE_IN_OUT) } }
        focus(keyed, EditorPanel.MoveTransform)
    }

    private suspend fun effect() {
        val layer = visualOrSample() ?: return
        val catalog = state.engine.effects.value
        val definition = catalog.find(SAMPLE_EFFECT)?.takeIf { it.available } ?: catalog.definitions.firstOrNull { it.available && it.category != "audio" } ?: return
        state.edit { it.update(layer.id, state.sampleMs) { l -> l.addEffect(definition.instance(null, l.endTime - l.startTime)) } }
        focus(layer, EditorPanel.Effects)
        state.effectIndex = (state.scene.layer(layer.id)?.effects?.size ?: 1) - 1
    }

    private suspend fun animation() {
        val layer = visualOrSample() ?: return
        state.edit { it.withAnimation(layer.id, MotionSlot.In, SAMPLE_MOTION, 600) }
        focus(layer, EditorPanel.Tools)
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SAMPLE_EFFECT = "absent.vignette"
        private const val SAMPLE_MOTION = "pop"
        val EASED = listOf(PropRef.Location, PropRef.Scale, PropRef.Rotation)
        private val EASE_IN_OUT = Easing("cubicBezier", listOf(0.42f, 0f, 0.58f, 1f))
    }
}
