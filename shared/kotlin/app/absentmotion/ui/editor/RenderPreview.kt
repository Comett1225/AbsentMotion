package app.absentmotion.ui.editor

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PreviewCache
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.presence
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

const val RENDER_PREVIEW_TAG = "render_preview"

internal class LiveHandoff {
    var requested by mutableLongStateOf(0L)
    var handoff by mutableLongStateOf(0L)
}

internal fun cacheMatches(state: EditorState, resources: Long): Boolean =
    PreviewCache.phase == PreviewCache.Phase.Ready && PreviewCache.key == PreviewCache.keyOf(state, resources) && state.soloMode == 0 && !state.pixelMode &&
        !state.gridMode && state.canvasZoom == 1f && state.canvasPan == Offset.Zero && (state.selectedId == null || state.playing) && state.drawing == null && state.panel != EditorPanel.EditText &&
        (state.cameraMode || state.scene.layers.none { it.kind == "camera" })

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PreRenderCell(state: EditorState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    val resources by state.engine.mediaRevision.collectAsState()
    val status by remember(state, resources) { derivedStateOf { PreviewCache.preRender(state, resources) } }
    val label = stringResource(if (status == PreviewCache.PreRender.Rendering) R.string.preview_cache_cancel_render else R.string.prerender)
    val described = when (status) {
        PreviewCache.PreRender.Ready -> stringResource(R.string.preview_cache_state_ready)
        PreviewCache.PreRender.Outdated -> stringResource(R.string.preview_cache_state_outdated)
        else -> null
    }
    val removeCopy = {
        if (status == PreviewCache.PreRender.Ready) {
            PreviewCache.remove()
            Toast.makeText(context, app.absentmotion.i18n.AppText.get(R.string.preview_cache_removed), Toast.LENGTH_SHORT).show()
        }
    }
    Box(
        modifier.fillMaxWidth().testTag(RENDER_PREVIEW_TAG).semantics { contentDescription = label; if (described != null) stateDescription = described }.rightClick(removeCopy).combinedClickable(
            onLongClick = removeCopy,
            onClick = {
                when (status) {
                    PreviewCache.PreRender.Rendering -> PreviewCache.cancel()
                    PreviewCache.PreRender.Ready -> Toast.makeText(context, PreviewCache.rangeLabel(context, state.scene.fps), Toast.LENGTH_LONG).show()
                    else -> PreviewCache.render(state, context, scope)
                }
            },
        ),
        Alignment.Center,
    ) {
        Text(
            if (status == PreviewCache.PreRender.Rendering) "${(PreviewCache.progress * 100).roundToInt()}%" else stringResource(R.string.prerender),
            color = when (status) {
                PreviewCache.PreRender.Rendering, PreviewCache.PreRender.Ready -> Am.Accent
                PreviewCache.PreRender.Outdated -> Am.Warning
                PreviewCache.PreRender.None -> Color.White
            },
            fontSize = 10.sp, lineHeight = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun PreRenderBadge(cachedPlaying: State<Boolean>, modifier: Modifier) {
    Presence(cachedPlaying.value && !PreviewCache.automatic) {
        Row(
            modifier.presence().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB3222222)).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Am.Accent))
            Text(stringResource(R.string.prerender_playing), Modifier.padding(start = 5.dp), color = Am.OnDark, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun CachedFrame(state: EditorState, usable: Boolean, live: LiveHandoff, left: Float, top: Float, width: Float, height: Float) {
    val player = PreviewCache.player
    if (!usable || player == null) return
    LaunchedEffect(player) {
        snapshotFlow { state.frame to state.playing }.collect { (frame, playing) -> if (PreviewCache.covers(frame)) player.want(frame, playing) }
    }
    val rendered by state.engine.rendered.collectAsState()
    val frame = state.frame
    val covered = PreviewCache.covers(frame)
    val handing = rendered.serial < live.handoff
    val scrubbing = covered && !state.playing && rendered.serial < live.requested && rendered.timeMs != state.timeMs
    if (!(covered && state.playing) && !handing && !scrubbing) return
    val background = Am.PreviewBg
    Canvas(Modifier.fillMaxSize()) {
        val shown = player.shown ?: return@Canvas
        if (shown.frame != frame) return@Canvas
        drawRect(background)
        drawImage(
            shown.image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Low,
        )
    }
}

@Composable
internal fun RenderPreviewNotes(state: EditorState, cachedPlaying: State<Boolean>, modifier: Modifier) {
    AutomaticPreview(state)
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        val startNs = System.nanoTime()
        val startFrame = state.frame
        val startRenders = state.engine.renders
        val startShows = PreviewCache.player?.shows ?: 0L
        var cachedTime = false
        try {
            while (true) {
                delay(250)
                val cached = cachedPlaying.value
                cachedTime = cachedTime || cached
            }
        } finally {
            Log.i("AMCache", "play cached=%b frames=%d renders=%d cachedShows=%d ms=%d".format(
                cachedTime, state.frame - startFrame, state.engine.renders - startRenders, (PreviewCache.player?.shows ?: 0L) - startShows,
                (System.nanoTime() - startNs) / 1_000_000))
        }
    }
    LaunchedEffect(state.exportOpen) {
        if (state.exportOpen) PreviewCache.cancel()
    }
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    val resources by state.engine.mediaRevision.collectAsState()
    val outdated by remember(state, resources) { derivedStateOf { PreviewCache.preRender(state, resources) == PreviewCache.PreRender.Outdated } }
    var stale by remember { mutableStateOf(false) }
    LaunchedEffect(outdated, state.playing) {
        stale = outdated
        if (outdated) {
            delay(STALE_NOTE_MS)
            stale = false
        }
    }
    val rendering = PreviewCache.phase == PreviewCache.Phase.Rendering
    Box(modifier.padding(top = 10.dp)) {
        Presence(stale && !rendering) {
            Note(Modifier.presence(rise = (-8).dp)) {
                Text(stringResource(R.string.prerender_outdated), Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2)
                TextButton({ stale = false; PreviewCache.render(state, context, scope) }) {
                    Text(stringResource(R.string.prerender_again), color = Am.Accent, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Presence(rendering) {
            Note(Modifier.presence(rise = (-8).dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.prerender_progress, (PreviewCache.progress * 100).roundToInt()), color = Am.OnDark, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Am.OnDark.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(PreviewCache.progress.coerceIn(0f, 1f)).height(3.dp).background(Am.Accent))
                    }
                }
                TextButton({ PreviewCache.cancel() }) { Text(stringResource(R.string.cancel), color = Am.Accent, fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
}

private const val STALE_NOTE_MS = 5_000L

private data class PreparationRequest(val key: app.absentmotion.editor.CacheKey, val frame: Int, val eligible: Boolean, val fit: Float, val cell: Float)

@Composable
private fun AutomaticPreview(state: EditorState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resources by state.engine.mediaRevision.collectAsState()
    val thermal = remember(context) { app.absentmotion.export.ThermalMonitor.of(context) }
    val enabled = state.settings.backgroundPreview
    LaunchedEffect(enabled) { if (!enabled && PreviewCache.automatic) PreviewCache.clear() }
    LaunchedEffect(state, lifecycle, resources, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            thermal.start()
            try {
                snapshotFlow {
                    PreparationRequest(PreviewCache.keyOf(state), state.frame,
                        !state.playing && !state.exportOpen && state.soloMode == 0 && !state.pixelMode && !state.gridMode && state.canvasZoom == 1f && state.canvasPan == Offset.Zero &&
                            state.drawing == null && state.panel != EditorPanel.EditText &&
                            (state.cameraMode || state.scene.layers.none { it.kind == "camera" }), PreviewCache.fit, PreviewCache.checkerCell)
                }.collectLatest { request ->
                    delay(100)
                    PreviewCache.follow(request.key)
                    if (!request.eligible) return@collectLatest
                    delay(PreviewCache.idleMs - 100)
                    while (!PreviewCache.idle()) delay(80)
                    fun current() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && PreviewCache.idle() && !thermal.hot &&
                        !state.playing && !state.exportOpen && state.scene == request.key.scene && state.frame == request.frame &&
                        state.settings.fullMotionBlur == request.key.accurateBlur && state.settings.androidEmoji == request.key.androidEmoji &&
                        state.settings.previewQuality.divisor == request.key.divisor &&
                        state.soloMode == 0 && !state.pixelMode && !state.gridMode && state.canvasZoom == 1f && state.canvasPan == Offset.Zero && state.drawing == null && state.panel != EditorPanel.EditText
                    if (current()) PreviewCache.prepare(state, context, ::current)
                }
            } finally { thermal.stop() }
        }
    }
}

@Composable
private fun Note(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.padding(horizontal = 16.dp).widthIn(max = 280.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.clip(RoundedCornerShape(14.dp)).background(Color(0xE6222222))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}
