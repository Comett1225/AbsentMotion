package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialGuard
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.absentmotion.editor.ChoppyWatch
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
internal fun RenderPreviewButton(state: EditorState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    val phase = PreviewCache.phase
    val resources by state.engine.mediaRevision.collectAsState()
    val current by remember(state, resources) { derivedStateOf { PreviewCache.key == PreviewCache.keyOf(state, resources) } }
    val label = stringResource(if (phase == PreviewCache.Phase.Rendering) R.string.preview_cache_cancel_render else R.string.preview_cache_render)
    val status = when {
        phase != PreviewCache.Phase.Ready -> null
        current -> stringResource(R.string.preview_cache_state_ready)
        else -> stringResource(R.string.preview_cache_state_outdated)
    }
    Box(
        modifier.testTag(RENDER_PREVIEW_TAG).tutorialGuard(any = true).semantics { contentDescription = label; if (status != null) stateDescription = status }.combinedClickable(
            onLongClick = {
                if (phase == PreviewCache.Phase.Ready) {
                    PreviewCache.clear()
                    Toast.makeText(context, app.absentmotion.i18n.AppText.get(R.string.preview_cache_removed), Toast.LENGTH_SHORT).show()
                }
            },
            onClick = {
                when {
                    phase == PreviewCache.Phase.Rendering -> PreviewCache.cancel()
                    phase == PreviewCache.Phase.Ready && current -> Toast.makeText(context, PreviewCache.rangeLabel(context, state.scene.fps), Toast.LENGTH_LONG).show()
                    else -> PreviewCache.render(state, context, scope)
                }
            },
        ),
        Alignment.Center,
    ) { RenderPreviewIcon(phase, current, PreviewCache.progress) }
}

@Composable
private fun RenderPreviewIcon(phase: PreviewCache.Phase, current: Boolean, progress: Float) {
    val dot = when {
        phase != PreviewCache.Phase.Ready -> null
        current -> Am.Accent
        else -> Am.Warning
    }
    val ink = Am.OnDark
    val ring = Am.TimelineBg
    val accent = Am.Accent
    Canvas(Modifier.size(26.dp, 24.dp)) {
        val u = size.height / 24f
        drawRoundRect(ink, Offset(2.5f * u, 5f * u), Size(19f * u, 14f * u), CornerRadius(2.5f * u), style = Stroke(1.6f * u))
        if (phase == PreviewCache.Phase.Rendering) {
            drawRect(ink, Offset(9.5f * u, 8f * u), Size(5f * u, 5f * u))
            drawLine(ink.copy(alpha = 0.3f), Offset(6f * u, 16f * u), Offset(18f * u, 16f * u), 1.6f * u, StrokeCap.Round)
            drawLine(accent, Offset(6f * u, 16f * u), Offset((6f + 12f * progress.coerceIn(0f, 1f)) * u, 16f * u), 1.6f * u, StrokeCap.Round)
        } else {
            val arrows = Path().apply {
                moveTo(7.5f * u, 8.5f * u); lineTo(11.5f * u, 12f * u); lineTo(7.5f * u, 15.5f * u); close()
                moveTo(12f * u, 8.5f * u); lineTo(16f * u, 12f * u); lineTo(12f * u, 15.5f * u); close()
            }
            drawPath(arrows, ink)
        }
        if (dot != null) {
            drawCircle(ring, 4.4f * u, Offset(21.5f * u, 5.5f * u))
            if (current) drawCircle(dot, 3.1f * u, Offset(21.5f * u, 5.5f * u))
            else drawCircle(dot, 2.4f * u, Offset(21.5f * u, 5.5f * u), style = Stroke(1.4f * u))
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
            shown.bitmap.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Low,
        )
    }
}

@Composable
internal fun RenderPreviewNotes(state: EditorState, cachedPlaying: State<Boolean>, modifier: Modifier) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    AutomaticPreview(state)
    var hint by remember { mutableStateOf(false) }
    val session = System.identityHashCode(state)
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        val watch = ChoppyWatch()
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
                if (cached || PreviewCache.phase == PreviewCache.Phase.Rendering) { watch.reset(); continue }
                if (watch.sample(System.nanoTime(), state.frame, state.engine.renders) && state.settings.previewCacheHint && PreviewCache.hinted != session) {
                    PreviewCache.hinted = session
                    hint = true
                }
            }
        } finally {
            Log.i("AMCache", "play cached=%b frames=%d renders=%d cachedShows=%d ms=%d".format(
                cachedTime, state.frame - startFrame, state.engine.renders - startRenders, (PreviewCache.player?.shows ?: 0L) - startShows,
                (System.nanoTime() - startNs) / 1_000_000))
        }
    }
    LaunchedEffect(hint) {
        if (hint) { delay(12_000); hint = false }
    }
    LaunchedEffect(state.exportOpen) {
        if (state.exportOpen) PreviewCache.cancel()
    }
    val rendering = PreviewCache.phase == PreviewCache.Phase.Rendering
    Box(modifier.padding(top = 10.dp)) {
        Presence(rendering) {
            Note(Modifier.presence(rise = (-8).dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.preview_cache_rendering, (PreviewCache.progress * 100).roundToInt()), color = Am.OnDark, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Am.OnDark.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(PreviewCache.progress.coerceIn(0f, 1f)).height(3.dp).background(Am.Accent))
                    }
                }
                TextButton({ PreviewCache.cancel() }) { Text(stringResource(R.string.cancel), color = Am.Accent, fontSize = 13.sp, maxLines = 1) }
            }
        }
        Presence(hint && !rendering) {
            Note(Modifier.presence(rise = (-8).dp)) {
                Text(stringResource(R.string.preview_cache_hint), Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 3)
                TextButton({ hint = false; PreviewCache.render(state, context, scope) }) {
                    Text(stringResource(R.string.preview_cache_hint_action), color = Am.Accent, fontSize = 13.sp, maxLines = 1)
                }
                Icon(
                    ChromeIcons.Close, stringResource(R.string.preview_cache_close),
                    Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { hint = false }.padding(10.dp), tint = Am.OnDarkDim,
                )
            }
        }
    }
}

private data class PreparationRequest(val key: app.absentmotion.editor.CacheKey, val frame: Int, val eligible: Boolean, val fit: Float, val cell: Float)

@Composable
private fun AutomaticPreview(state: EditorState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resources by state.engine.mediaRevision.collectAsState()
    val rendered by state.engine.rendered.collectAsState()
    val thermal = remember(context) { app.absentmotion.export.ThermalMonitor.of(context) }
    LaunchedEffect(state, lifecycle, resources, rendered) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            thermal.start()
            try {
                snapshotFlow {
                    PreparationRequest(PreviewCache.keyOf(state), state.frame,
                        !state.playing && !state.exportOpen && state.soloMode == 0 && !state.pixelMode && !state.gridMode && state.canvasZoom == 1f && state.canvasPan == Offset.Zero &&
                            state.drawing == null && state.panel != EditorPanel.EditText &&
                            (state.cameraMode || state.scene.layers.none { it.kind == "camera" }), PreviewCache.fit, PreviewCache.checkerCell)
                }.collectLatest { request ->
                    if (!request.eligible) return@collectLatest
                    delay(250)
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
