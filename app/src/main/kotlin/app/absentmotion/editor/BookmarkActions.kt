package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.Context
import app.absentmotion.ai.speechPcm
import app.absentmotion.audio.Beats
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.soundUri
import app.absentmotion.model.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.roundToInt

fun EditorState.toggleBookmark() = edit { it.withBookmarkToggled(frame) }

suspend fun EditorState.markBeats(context: Context): Int {
    val layer = selectedId?.let(scene::layer)?.takeIf { it.soundUri != null } ?: error(AppText.get(R.string.edit_beats_select_layer))
    val fps = scene.fps
    val alone = Scene(scene.node.copy(children = scene.node.children.filter { it.name !in Scene.LAYER_TAGS } +
        layer.node.with("hidden", null).let { it.copy(children = it.children.filterNot { c -> c.name == "gain" }) }))
    val mix = OfflineMix.of(context, alone, Resampler.HIGH) ?: error(AppText.get(R.string.edit_beats_no_sound))
    val pcm = try {
        withContext(Dispatchers.IO) { speechPcm(mix, layer.startTime, layer.endTime + 1) {} }
    } finally {
        (mix as? Closeable)?.close()
    }
    val beats = withContext(Dispatchers.Default) { Beats.detect(pcm, 16000) }
    val first = scene.frameAt(layer.startTime)
    val last = scene.frameAt(layer.endTime)
    val frames = beats.map { (((layer.startTime + it) * fps / 1000.0).roundToInt()) }.filter { it in first..last }.distinct()
    var added = 0
    edit { s ->
        val existing = s.bookmarks
        val times = frames.map(s::frameStart).filter { t -> existing.none { abs(it - t) <= 1000.0 / fps } }
        added = times.size
        s.withBookmarks(times)
    }
    return added
}
