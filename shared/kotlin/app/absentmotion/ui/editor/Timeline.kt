package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialGuard
import app.absentmotion.model.Scene
import app.absentmotion.model.shownName
import app.absentmotion.model.defaultCameraId
import androidx.compose.ui.unit.DpSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.resizeCursor
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.absentmotion.R
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.absentmotion.platform.SystemClock
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.absentmotion.editor.renameLayer
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.canPaste
import app.absentmotion.editor.split
import app.absentmotion.editor.groupLayersCentred
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.isPlainGroup
import app.absentmotion.editor.askUngroup
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PreviewCache
import app.absentmotion.editor.toggleBookmark
import app.absentmotion.editor.toggleHidden
import app.absentmotion.editor.deselectLayer
import app.absentmotion.editor.clearSelection
import app.absentmotion.media.VideoProxies
import app.absentmotion.model.LabelColour
import app.absentmotion.model.Layer
import app.absentmotion.ai.trimmedClip
import app.absentmotion.ai.withClip
import app.absentmotion.model.KeyScope
import app.absentmotion.model.withKeyframesMoved
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.tutorial.TourFacts
import app.absentmotion.ui.tutorial.kindOf
import app.absentmotion.ui.tutorial.tutorialRegion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private val RulerHeight = 42.dp
private val RowHeight = if (PlatformFeatures.desktop) 30.dp else 28.dp


private val PlayheadWidth = 1.5.dp
private val TickWidth = 1.dp
private val TickShort = 5.dp
private val TickHalfSecond = 12.dp
private val TickSecond = 18.dp
private const val MIN_TICK_GAP_DP = 3f

private val PillWidth = 70.dp
private val PillTop = 17.8.dp
private val PillHeight = 16.7.dp
private val PillNotch = 5.dp

private val ReadoutHalfWidth = PillWidth / 2 + 12.dp
private val PillInset = 2.dp
private const val SelectedRowShade = 0.16f
private val ReadoutTop = PillTop + PillHeight / 2 - 24.dp

private val BookmarkColor get() = Am.Bookmark
private const val BOOKMARK_ALPHA = 0.58f
private val BookmarkLineWidth = 0.7.dp
private val BookmarkFlagTop = 2.1.dp
private val BookmarkFlagBottom = 5.dp
private val BookmarkFlagSide = 2.1.dp
private val BookmarkPeakHalf = 2.5.dp
private val PillOutline = 1.1.dp

internal val HeaderWidth = 62.dp
private val HeaderInset = 1.dp
private val TagStripWidth = 2.1.dp
private val EyeZone = 36.dp
private val EyeCentre = 17.dp
private val ThumbLeft = 36.dp
private val ThumbSize = 16.dp
private val ClipTrim = 8.dp
private val DeskClipTrim = 12.dp
private const val HIDDEN_ALPHA = 0.55f

private val BarInset = 2.5.dp
private val BarRadius = if (PlatformFeatures.desktop) 4.dp else 2.dp
private val BarShadowHeight = 2.dp
private val LabelInset = 14.dp
private const val MAX_LABEL_PX = 16_000

private val ProxyLineHeight = 2.dp
private val ProxyLineInset = 1.dp

private val GripZone = 30.dp
private val GripGap = 1.8.dp

private val HandleInset = 9.dp
private val HandleZone = 22.dp

private val MultiBarHeight = 48.dp

private val SnapZone = 8.dp
private val SnapLine = 1.dp

private const val LIFT_SCALE = 1.02f
private const val LIFT_DIM = 0.3f
private const val LIFT_SHADOW_ALPHA = 0.07f
private val LiftShadowStep = 1.5.dp
private const val LIFT_MS = 100
private const val SLIDE_MS = 120
private const val DROP_MS = 150
private val PushZone = if (PlatformFeatures.desktop) 28.dp else 56.dp
private const val PUSH_SLOWEST_ROWS = 1f
private const val PUSH_FASTEST_ROWS = 12f
private const val PUSH_MAX_STEP_S = 0.1f

private const val MIN_ZOOM = 10f
private const val FAR_OUT_MS = 600_000f
private const val MAX_ZOOM = 1000f

private val TickColor get() = Am.Line
private val CacheBarColor = Color(0xFF45C46A)
private val CacheBarHeight = 3.dp
private val RulerMajor get() = Am.Inactive
private val RulerMinor get() = Am.Inactive.copy(alpha = 0.7f)
private val RetimeMarkColor get() = Am.Gold.copy(alpha = 0.67f)
private val PillColor get() = Am.Button
private val HeaderFill get() = Am.Button
private val HeaderEdge get() = Am.Line
private val BarStripe get() = Am.LayerBarStripe
private val TextBar get() = Am.TextBar
private val TextStripe get() = Am.TextBarStripe
private val CameraBar = Color(0xFFC32F5B)
private val CameraStripe = Color(0xFFC83D66)
private val BarShadow get() = Am.PreviewBg
private val GripColor get() = Am.Inactive
private val HandleColor get() = Am.Inactive
private val LimitColor get() = Am.Warning
private val SnapColor get() = Am.Accent


private class TimelineStyles {
    val ruler = TextStyle(color = Am.OnDarkDim, fontSize = 12.sp)
    val timecode = TextStyle(color = Am.OnDark, fontSize = 14.sp)
    val label = TextStyle(color = Am.OnBar, fontSize = if (PlatformFeatures.desktop) 12.sp else 11.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content)
    val mediaLabel = label.copy(color = Color.White)
    val audioLabel = label.copy(color = Am.OnAudioBar)
    val header = TextStyle(color = Am.OnDark, fontSize = 13.sp)
    val hiddenHeader = TextStyle(color = Am.OnDarkDim, fontSize = 13.sp)
}

@Composable
fun Timeline(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val wide = LocalWide.current
    val view = remember(state, density, wide) {
        TimelineView(state, density, scope, wide).also { v ->
            v.onTick = { if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = if (PlatformFeatures.desktop) 128 else 32)
    val styles = remember(Am.palette) { TimelineStyles() }
    val eye = rememberVectorPainter(TimelineIcons.Eye)
    val eyeOff = rememberVectorPainter(TimelineIcons.EyeOff)
    val outline = remember { Path() }
    val strips by remember(view) { derivedStateOf { view.stripWindow() } }
    val proxies by VideoProxies.progress.collectAsState()
    val mediaPictures = buildMap {
        for (row in strips.rows) {
            val layer = view.layerAt(row) ?: continue
            val info = layer.mediaFill?.let { state.scene.media(it.uri) }
            val turned = info?.orientation == 90 || info?.orientation == 270
            val width = (if (turned) info.height else info?.width)?.coerceAtLeast(1) ?: 1
            val height = (if (turned) info.width else info?.height)?.coerceAtLeast(1) ?: 1
            val tileMs = 23f * width / height * 1000 / view.dpPerSecond
            key(layer.id) { mediaStripPicture(layer, state.scene, state.engine, strips.fromMs, strips.toMs, tileMs)?.let { put(layer.id, it) } }
        }
    }

    LoadWaveforms(state.scene)
    LaunchedEffect(view, state.selectedId) { if (PlatformFeatures.desktop) view.revealRow() else view.revealSelected() }
    val appear = remember(state.selectedId) { Animatable(if (state.selectedId == null) 1f else 0f) }
    LaunchedEffect(appear) { appear.animateTo(1f, Motion.enter()) }
    LaunchedEffect(view, state.playing) { if (state.playing) view.followPlayback() }
    if (PlatformFeatures.desktop) LaunchedEffect(view) {
        snapshotFlow { state.frame to state.playing }.collect { (_, playing) -> if (playing) view.pagePlayback() else view.revealPlayhead() }
    }
    if (PlatformFeatures.desktop) app.absentmotion.platform.BackHandler(view.desktopDragging || (state.drawing !is app.absentmotion.editor.PointMode && (state.selectedId != null || MultiSelect.active))) {
        if (!view.cancelDesktopDrag()) state.clearSelection()
    }
    if (PlatformFeatures.desktop) app.absentmotion.platform.BackHandler(view.menuAt != null) { view.menuAt = null }
    if (PlatformFeatures.desktop) app.absentmotion.platform.BackHandler(view.renameId != null) { view.renameId = null }
    if (PlatformFeatures.desktop) DisposableEffect(view) {
        DeskTimelineControls.zoom = { view.zoomBy(it) }
        DeskTimelineControls.fit = { view.fit() }
        DeskDrop.frameAt = { point -> view.frameAtWindow(point) }
        onDispose {
            DeskTimelineControls.zoom = null
            DeskTimelineControls.fit = null
            DeskDrop.frameAt = { null }
        }
    }

    TimelineSurface(
        view,
        modifier
            .then(if (PlatformFeatures.desktop) Modifier else app.absentmotion.platform.wheel { delta, at, zoom ->
                if (zoom) return@wheel view.zoomBy(1.1f.pow(-delta.y))
                view.stopMotion(); state.playing = false
                val horizontal = kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y) || at.y < view.rulerH
                view.dragBy(if (horizontal) Drag.Scrub else Drag.Scroll, if (horizontal) Offset(-(if (delta.x != 0f) delta.x else delta.y) * 18f, 0f) else Offset(0f, -delta.y * 18f), at)
                view.finishWheelScrub()
            })
            .onSizeChanged { view.resizeViewport(it) }
            .then(if (PlatformFeatures.desktop) Modifier.onGloballyPositioned { view.coordinates = it } else Modifier)
            .tutorialRegion("ruler") { listOf(Rect(0f, 0f, view.size.width.toFloat(), view.rulerH)) }
            .tutorialRegion("clip") { view.clipRows(media = false) }
            .tutorialRegion("clip_media") { view.clipRows(media = true) }
            .pointerInput(view) {
                if (PlatformFeatures.desktop) desktopTimelineGestures(view)
                else timelineGestures(view) { if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
            .drawBehind { drawTimeline(view, measurer, styles, eye, eyeOff, outline, mediaPictures, proxies, appear.value) },
    ) {
        HeaderPictures(view) { drawLifted(view, measurer, styles, eye, eyeOff, outline, mediaPictures, appear.value) }
        RowSemantics(view)
        if (PlatformFeatures.desktop) {
            DesktopTimelineMenu(view)
            DesktopTimelineRename(view)
        }
    }
}

@Composable
private fun TimelineSurface(view: TimelineView, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val cursor = if (!PlatformFeatures.desktop) Modifier else if (view.resizeHover) Modifier.resizeCursor() else Modifier.pointerHoverIcon(if (view.handHover) PointerIcon.Hand else PointerIcon.Default)
    Box(modifier.then(cursor), content = content)
}

@Composable
private fun DesktopTimelineMenu(view: TimelineView) {
    var stretch by remember { mutableStateOf<Set<Long>?>(null) }
    val scope = rememberCoroutineScope()
    stretch?.let { StretchSheet(view.state, it) { stretch = null } }
    val at = view.menuAt ?: return
    val state = view.state
    val id = view.menuId
    val layer = id?.let { state.scene.layer(it) }
    val menuColors = MenuDefaults.itemColors(textColor = Am.OnDark, disabledTextColor = Am.OnDark.copy(alpha = 0.38f))
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = { view.menuAt = null }, containerColor = Am.Field) {
            fun close(action: () -> Unit) { view.menuAt = null; action() }
            if (id != null) {
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.timeline_cut), fontSize = 14.sp) }, onClick = { close { state.copySelected(); state.deleteChosen() } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.crash_copy), fontSize = 14.sp) }, onClick = { close { state.copySelected() } })
            }
            DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.downloader_paste), fontSize = 14.sp) }, enabled = state.canPaste, onClick = { close { state.pasteLayer() } })
            if (id != null) {
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.home_duplicate), fontSize = 14.sp) }, onClick = { close { state.duplicateChosen() } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.timeline_split), fontSize = 14.sp) }, onClick = { close { state.split() } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.delete), fontSize = 14.sp) }, onClick = { close { state.deleteChosen() } })
                if (MultiSelect.active && MultiSelect.ids.size > 1) DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.structure_group), fontSize = 14.sp) }, onClick = { close { scope.launch { state.groupLayersCentred(MultiSelect.ids) } } })
                if (MultiSelect.active && MultiSelect.ids.size > 1) DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.multi_stretch_timing), fontSize = 14.sp) }, enabled = state.chosenLayers.any { it.endTime > it.startTime }, onClick = { close { state.playing = false; stretch = MultiSelect.ids } })
                if (layer?.isPlainGroup == true && !MultiSelect.active) {
                    DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.layer_tool_edit_group), fontSize = 14.sp) }, onClick = { close { state.enterGroup(id) } })
                    DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.layer_tool_ungroup), fontSize = 14.sp) }, onClick = { close { state.askUngroup() } })
                }
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(if (layer?.hidden == true) R.string.a11y_show_layer else R.string.a11y_hide_layer), fontSize = 14.sp) }, onClick = { close { state.toggleHidden(id) } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.rename), fontSize = 14.sp) }, enabled = !MultiSelect.active, onClick = { close { view.renameId = id } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.timeline_bring_forward), fontSize = 14.sp) }, onClick = { close { state.edit { scene -> scene.reorder(id, (scene.layers.indexOfFirst { it.id == id } + 1).coerceAtMost(scene.layers.lastIndex)) } } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.timeline_send_backward), fontSize = 14.sp) }, onClick = { close { state.edit { scene -> scene.reorder(id, (scene.layers.indexOfFirst { it.id == id } - 1).coerceAtLeast(0)) } } })
            } else {
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.timeline_add_bookmark), fontSize = 14.sp) }, onClick = { close { if (state.scene.bookmarks.none { state.scene.frameAt(it) == state.frame }) state.toggleBookmark() } })
                DropdownMenuItem(colors = menuColors, text = { Text(stringResource(R.string.menu_select_all_layers), fontSize = 14.sp) }, onClick = { close { state.deselectLayer(); MultiSelect.ids = state.scene.layers.map { it.id }.toSet(); MultiSelect.active = MultiSelect.ids.isNotEmpty() } })
            }
        }
    }
}

@Composable
private fun DesktopTimelineRename(view: TimelineView) {
    val id = view.renameId ?: return
    val layer = view.state.scene.layer(id) ?: return
    var name by remember(id) { mutableStateOf(layer.label) }
    val focus = remember(id) { FocusRequester() }
    fun confirm() {
        if (name.isNotBlank()) view.state.renameLayer(id, name.trim())
        view.renameId = null
    }
    AlertDialog(
        onDismissRequest = { view.renameId = null },
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(name, { name = it }, Modifier.focusRequester(focus), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { confirm() }))
            LaunchedEffect(id) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { confirm() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { view.renameId = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RowSemantics(view: TimelineView) {
    val rows by remember(view) { derivedStateOf { view.visibleRows() } }
    val select = stringResource(R.string.a11y_select_layer)
    val hide = stringResource(R.string.a11y_hide_layer)
    val show = stringResource(R.string.a11y_show_layer)
    val hidden = stringResource(R.string.a11y_layer_hidden)
    Box(Modifier.fillMaxSize().padding(top = RulerHeight).clipToBounds()) {
        for (row in rows) {
            val layer = view.layerAt(row) ?: continue
            key(layer.id) {
                val chosen = layer.id == view.state.selectedId || layer.id in MultiSelect.ids
                Box(Modifier.offset { IntOffset(0, (view.rowTop(row) - view.rulerH).roundToInt()) }.fillMaxWidth().height(RowHeight).tutorialGuard(any = true).semantics {
                    contentDescription = layer.shownName()
                    selected = chosen
                    if (layer.hidden) stateDescription = hidden
                    onClick(select) { view.tap(Hit.Header(layer.id)); true }
                    customActions = if (app.absentmotion.ui.tutorial.Tutorial.running) emptyList() else listOf(CustomAccessibilityAction(if (layer.hidden) show else hide) { view.state.toggleHidden(layer.id); true })
                })
            }
        }
    }
}

@Composable
private fun HeaderPictures(view: TimelineView, drawLift: DrawScope.() -> Unit) {
    val rows by remember(view) { derivedStateOf { view.visibleRows() } }
    Box(Modifier.fillMaxSize().padding(top = RulerHeight).clipToBounds()) {
        for (row in rows) {
            val layer = view.layerAt(row)
            if (layer != null) {
                key(layer.id) {
                    val lifted = layer.id == view.liftId
                    if (PlatformFeatures.desktop) {
                        val eyeLabel = stringResource(if (layer.hidden) R.string.a11y_show_layer else R.string.a11y_hide_layer)
                        Box(Modifier.offset { IntOffset(0, (view.rowTop(row) - view.rulerH).roundToInt()) }) {
                            app.absentmotion.platform.Hint(eyeLabel) { Box(Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)) }
                        }
                    }
                    LayerThumbnail(
                        layer, view.state.engine,
                        Modifier
                            .zIndex(if (lifted) 2f else 0f)
                            .offset {
                                val top = view.shownTop(row, layer.id) - view.rulerH + (RowHeight - ThumbSize).toPx() / 2
                                IntOffset(ThumbLeft.roundToPx(), top.roundToInt())
                            }
                            .then(if (lifted && !PlatformFeatures.desktop) Modifier.graphicsLayer {
                                val grow = 1 + (LIFT_SCALE - 1) * view.liftAmount
                                scaleX = grow
                                scaleY = grow
                                transformOrigin = TransformOrigin((view.size.width / 2f - ThumbLeft.toPx()) / ThumbSize.toPx(), 0.5f)
                            } else Modifier)
                            .size(ThumbSize)
                            .alpha(if (layer.hidden) HIDDEN_ALPHA else 1f),
                    )
                }
            }
        }
        if (view.liftId != null && !PlatformFeatures.desktop) Box(Modifier.fillMaxSize().zIndex(1f).drawBehind(drawLift))
    }
}

internal object TimelineMath {
    fun timeToX(timeMs: Float, nowMs: Float, originX: Float, pxPerMs: Float): Float = originX + (timeMs - nowMs) * pxPerMs

    fun xToTime(x: Float, nowMs: Float, originX: Float, pxPerMs: Float): Float = nowMs + (x - originX) / pxPerMs

    fun frameTimeMs(frame: Int, fps: Int): Float = frame * 1000f / fps

    fun nearestFrame(timeMs: Float, fps: Int): Int = (timeMs * fps / 1000f).roundToInt()

    fun timecode(frame: Int, fps: Int): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${two(frame / fps / 60)}:${two(frame / fps % 60)}:${two(frame % fps)}"
    }

    fun tickStep(fps: Int, dpPerSecond: Float): Int =
        (1..fps).firstOrNull { fps % it == 0 && it * dpPerSecond / fps >= MIN_TICK_GAP_DP } ?: fps

    fun rowsBottom(height: Float, panelTop: Float?, barHeight: Float): Float = min(panelTop ?: height, height - barHeight)

    fun maxScroll(rows: Int, rowH: Float, top: Float, bottom: Float): Float = max(0f, rows * rowH - (bottom - top))

    fun centredScroll(row: Int, rowH: Float, top: Float, bottom: Float): Float = (row + 0.5f) * rowH - (bottom - top) / 2

    fun visibleRows(rows: Int, rowH: Float, top: Float, bottom: Float, scroll: Float): IntRange =
        max(0, floor(scroll / rowH).toInt())..min(rows - 1, ceil((bottom - top + scroll) / rowH).toInt() - 1)

    fun released(end: PointerInputChange?): Boolean = end == null || !end.isConsumed

    fun aboveAddMenu(y: Float, height: Float, menu: Float, ruler: Float): Boolean = y >= 0f && y < minOf(height - menu, ruler)

    fun snapFrame(wantedMs: Float, targets: Collection<Int>, fps: Int, zoneMs: Float): Int? {
        val best = targets.minByOrNull { abs(frameTimeMs(it, fps) - wantedMs) } ?: return null
        return best.takeIf { abs(frameTimeMs(it, fps) - wantedMs) <= zoneMs }
    }

    fun moveTarget(wantedMs: Float, spanMs: Float, spanFrames: Int, targets: Collection<Int>, fps: Int, zoneMs: Float): MoveTarget {
        val snapped = listOf(0f to 0, spanMs to spanFrames).flatMap { (offsetMs, offsetFrames) ->
            targets.map { hit -> Triple(hit - offsetFrames, hit, abs(frameTimeMs(hit, fps) - wantedMs - offsetMs)) }
        }.filter { it.first >= 0 && it.third <= zoneMs }.minByOrNull { it.third }
        return snapped?.let { MoveTarget(it.first, it.second) } ?: MoveTarget(nearestFrame(wantedMs, fps).coerceAtLeast(0), null)
    }

    fun movedStart(scene: Scene, layer: Layer, frame: Int): Int =
        if (frame == scene.firstShownFrame(layer.startTime)) layer.startTime else scene.frameStart(frame)

    fun snapTargets(scene: Scene, skip: Long, playhead: Int): Set<Int> = snapTargets(scene, setOf(skip), playhead)

    fun snapTargets(scene: Scene, skip: Set<Long>, playhead: Int): Set<Int> = buildSet {
        add(playhead)
        scene.bookmarks.forEach { add(scene.firstShownFrame(it)) }
        for (layer in scene.layers) if (layer.id !in skip) {
            add(scene.firstShownFrame(layer.startTime))
            add(scene.lastShownFrame(layer.endTime) + 1)
            layer.keyframeTimes().forEach { add(scene.frameAt(it)) }
        }
    }

    fun groupTarget(scene: Scene, grabbed: Layer, others: List<Layer>, found: MoveTarget): MoveTarget {
        if (others.isEmpty()) return found
        val first = scene.firstShownFrame(grabbed.startTime)
        val lowest = minOf(first, others.minOf { scene.firstShownFrame(it.startTime) })
        return if (found.frame - first >= -lowest) found else MoveTarget(first - lowest, null)
    }

    fun movedClips(scene: Scene, grabbed: Layer, others: List<Layer>, frame: Int): Scene {
        val shift = frame - scene.firstShownFrame(grabbed.startTime)
        return (listOf(grabbed) + others).fold(scene) { acc, layer ->
            acc.update(layer.id) { acc.movedClip(layer, movedStart(acc, layer, acc.firstShownFrame(layer.startTime) + shift)) }
        }
    }

    fun edgeMs(layer: Layer, start: Boolean): Float = (if (start) layer.startTime else layer.endTime + 1).toFloat()

    fun trimmedEdge(from: Scene, base: Layer, start: Boolean, wantedMs: Float): EdgeTrim {
        val frame = nearestFrame(wantedMs, from.fps)
        val edge = if (start) frame.coerceIn(0, from.lastShownFrame(base.endTime).coerceAtLeast(0)) else (frame - 1).coerceAtLeast(from.firstShownFrame(base.startTime)) + 1
        val time = if (start) from.frameStart(edge) else from.frameEnd(edge - 1)
        val trimmed = if (start) from.trimmedClip(base, time, base.endTime) else from.trimmedClip(base, base.startTime, time)
        return EdgeTrim(trimmed, edge, time, if (start) trimmed.startTime > time else trimmed.endTime < time)
    }

    fun trimmedEdges(from: Scene, grabbed: Layer, others: List<Layer>, start: Boolean, wantedMs: Float): List<EdgeTrim> {
        val anchor = edgeMs(grabbed, start)
        return (listOf(grabbed) + others).map { trimmedEdge(from, it, start, wantedMs + (edgeMs(it, start) - anchor)) }
    }
}

internal data class MoveTarget(val frame: Int, val snap: Int?)

internal class EdgeTrim(val layer: Layer, val edge: Int, val time: Int, val limited: Boolean)

private data class StripWindow(val rows: IntRange, val fromMs: Float, val toMs: Float)

private const val STRIP_WINDOW_MS = 2000f

private class TimeScale(val nowMs: Float, val originX: Float, val pxPerMs: Float) {
    fun x(timeMs: Float) = TimelineMath.timeToX(timeMs, nowMs, originX, pxPerMs)
    fun time(x: Float) = TimelineMath.xToTime(x, nowMs, originX, pxPerMs)
    fun slid(dx: Float) = TimeScale(nowMs, originX + dx, pxPerMs)
}

private sealed interface Hit {
    data object Ruler : Hit
    data object Corner : Hit
    data object Empty : Hit

    data object Readout : Hit
    data class Eye(val id: Long) : Hit

    data class Bar(val id: Long) : Hit
    data class Key(val id: Long, val frame: Int) : Hit
    data class Header(val id: Long) : Hit
    data class Grip(val id: Long, val start: Boolean) : Hit
    data class Handle(val id: Long) : Hit
    data class Row(val id: Long) : Hit
}

private enum class Drag(val edit: Boolean = false) { Scrub, Scroll, Pinch, Spent, Select, TrimStart(true), TrimEnd(true), Reorder(true), Lift(true), Key(true) }

private const val KEY_MOVE = "keyframe-move"
private const val MOVE_EDIT = "clip-move"


private class TimelineView(val state: EditorState, density: Density, private val scope: CoroutineScope, private val wide: Boolean) {
    private val scale = density.density
    private val Dp.px get() = value * scale
    private val decay = splineBasedDecay<Float>(density)

    val rulerH = RulerHeight.px
    val rowH = RowHeight.px
    var coordinates: LayoutCoordinates? = null
    var headerDp by mutableFloatStateOf(DeskTimelineControls.headerDp)
    val headerWidth: Float get() = if (PlatformFeatures.desktop) headerDp * scale else HeaderWidth.px
    private var viewStartMs by mutableFloatStateOf(0f)
    var desktopCancel by mutableStateOf(false)
    var rubber by mutableStateOf<Rect?>(null)
    var resizingHeader by mutableStateOf(false)
    var hoveredId by mutableStateOf<Long?>(null)
    var resizeHover by mutableStateOf(false)
    var handHover by mutableStateOf(false)
    var hoveredKey by mutableStateOf<Pair<Long, Int>?>(null)
    var menuAt by mutableStateOf<Offset?>(null)
    var menuId by mutableStateOf<Long?>(null)
    var renameId by mutableStateOf<Long?>(null)
    var snapDisabled = false

    var size by mutableStateOf(IntSize.Zero)
        private set
    private var initialFit = false
    fun resizeViewport(value: IntSize) {
        size = value
        if (PlatformFeatures.desktop && value.width > headerWidth && value.height > 0) {
            if (!initialFit) { initialFit = true; fit() } else refit()
        }
    }

    val dpPerSecond: Float get() = state.timelineZoom
    private var scrollPx by mutableFloatStateOf(0f)

    private var scrubMs by mutableStateOf<Float?>(null)
    private var scrubFrame = 0
    private var motion: Job? = null

    private var dragId = 0L
    private var edgeMs = 0f
    private var trimBase: Layer? = null
    private var trimScene: Scene? = null
    private var trimGroup: List<Layer> = emptyList()
    var limits by mutableStateOf<Map<Long, Boolean>>(emptyMap())
        private set
    var onTick: () -> Unit = {}
    var snapAt by mutableStateOf<Int?>(null)
        private set
    private var snapTargets = emptySet<Int>()

    private class Slide(val from: Float, val at: Long)

    private var selectAnchor: Long? = null
    private var selectBase = emptySet<Long>()
    private var selectAdd = true
    private var selectStart = 0f
    private var selectFinger = 0f
    private var selectSlop = 0f
    private var selectPush = true
    private var selectArmed = false
    private var selectRows = 1

    var liftId by mutableStateOf<Long?>(null)
        private set
    private var liftTop by mutableFloatStateOf(0f)
    private var frameMs by mutableLongStateOf(0L)
    private var liftJob: Job? = null
    private var liftAt = 0L
    private var liftGrab = 0f
    private var liftFinger = 0f
    private var liftStart = 0f
    private var liftSlop = 0f
    private var liftArmed = false
    private var liftPush = true
    private var dropping = false
    private var dropAt = 0L
    private var dropFrom = 0f
    private val slides = HashMap<Long, Slide>()

    var keyId by mutableStateOf<Long?>(null)
        private set
    var keyFrame by mutableIntStateOf(0)
        private set
    private var keyFrom = 0
    private var keyMs = 0f
    private var keyLayer: Layer? = null
    private var keyScope: KeyScope? = null

    var moveId by mutableStateOf<Long?>(null)
        private set
    var moveSlide by mutableFloatStateOf(0f)
        private set
    private var moveLayer: Layer? = null
    private var moveGroup: List<Layer> = emptyList()
    private var moveGroupIds: Set<Long> = emptySet()
    private var moveBase: Scene? = null
    private var moveDx = 0f
    private var moveArmed = false
    private var liftMoves = false
    private var liftStartX = 0f

    val liftMoved: Boolean get() = liftArmed || moveArmed

    private val layersState = derivedStateOf(referentialEqualityPolicy()) { state.scene.layers }

    val layers: List<Layer> get() = layersState.value
    val fps: Int get() = state.scene.fps.coerceAtLeast(1)

    private val nowMs: Float
        get() = scrubMs?.takeIf { state.frame == scrubFrame } ?: TimelineMath.frameTimeMs(state.frame, fps)

    fun timeScale(): TimeScale {
        val pxPerMs = dpPerSecond * scale / 1000f
        val origin = if (PlatformFeatures.desktop) headerWidth + (nowMs - viewStartMs) * pxPerMs else (size.width - PlayheadWidth.px) / 2f
        return TimeScale(nowMs, origin, pxPerMs)
    }

    fun frameAtWindow(point: Offset): Int? {
        val local = coordinates?.takeIf { it.isAttached }?.windowToLocal(point) ?: return null
        if (local.x < headerWidth || local.x >= size.width || local.y < rulerH || local.y >= rowsBottom) return null
        return TimelineMath.nearestFrame(timeScale().time(local.x), fps).coerceAtLeast(0)
    }

    private val maxZoom: Float get() = fps * 40f

    private val fitZoom: Float
        get() = ((size.width - headerWidth).coerceAtLeast(1f) / scale * 1000f / TimelineMath.frameTimeMs(state.scene.lastFrame + 1, fps).coerceAtLeast(1f)).coerceAtMost(maxZoom)

    private val minZoom: Float get() = min(fitZoom / 2f, (size.width - headerWidth).coerceAtLeast(1f) / scale * 1000f / FAR_OUT_MS)

    private val endMs: Float
        get() = max(TimelineMath.frameTimeMs(state.scene.lastFrame + 1, fps), (layers.maxOfOrNull { it.endTime + 1 } ?: 0).toFloat())

    private val visibleMs: Float get() = (size.width - headerWidth).coerceAtLeast(0f) / timeScale().pxPerMs

    private var followFit = true

    val rowsEnd: Float get() = rowsBottom
    val maxScroll: Float get() = TimelineMath.maxScroll(layers.size, rowH, rulerH, rowsBottom)
    private val panMax: Float get() = (endMs - visibleMs / 2f).coerceAtLeast(0f)
    val panSpan: Float get() = (visibleMs / (panMax + visibleMs).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val panFraction: Float get() = if (panMax > 0f) viewStartMs / panMax else 0f

    private fun setViewStart(value: Float) {
        viewStartMs = value.coerceIn(0f, panMax)
    }

    fun refit() {
        if (followFit && size.width > headerWidth) fit()
    }

    fun pagePlayback() {
        if (size.width <= headerWidth) return
        val x = timeScale().originX
        if (x >= size.width || x < headerWidth) setViewStart(nowMs)
    }

    fun revealRow() {
        val row = state.selectedId?.let { rowOf(it) } ?: return
        if (desktopDragging) return
        val top = rowTop(row)
        val bottom = top + rowH
        if (top < rulerH) scrollPx = scroll - (rulerH - top) else if (bottom > rowsBottom) scrollPx = scroll + (bottom - rowsBottom)
    }

    fun revealPlayhead() {
        if (size.width <= headerWidth || desktopDragging) return
        val x = timeScale().originX
        if (x > size.width || x < headerWidth) setViewStart(nowMs - visibleMs * 0.1f)
    }

    fun pan(dx: Float) {
        setViewStart(viewStartMs + dx / timeScale().pxPerMs)
    }

    fun seek(x: Float) {
        state.playing = false
        state.frame = TimelineMath.nearestFrame(timeScale().time(x.coerceIn(headerWidth, size.width.toFloat())), fps).coerceIn(0, state.scene.lastFrame)
        scrubMs = null
    }

    fun fit() {
        state.timelineZoom = fitZoom
        followFit = true
        viewStartMs = 0f
    }

    fun wheel(delta: Offset, at: Offset, zoom: Boolean, sideways: Boolean) {
        stopMotion()
        when {
            zoom -> zoomBy(1.12f.pow(-delta.y), at.x)
            sideways || abs(delta.x) > abs(delta.y) -> pan((if (delta.x != 0f) delta.x else delta.y) * 48.dp.px)
            else -> scrollBy(-delta.y * 32.dp.px)
        }
    }

    fun hover(at: Offset?) {
        val hit = at?.let { hitAt(it) }
        hoveredId = hit?.let { clipId(it) }
        resizeHover = hit is Hit.Grip || at != null && nearHeaderEdge(at.x)
        handHover = hit is Hit.Eye || hit is Hit.Header || hit is Hit.Bar || hit is Hit.Key || hit == Hit.Readout || hit == Hit.Ruler
        hoveredKey = (hit as? Hit.Key)?.let { it.id to it.frame }
    }

    fun clipId(hit: Hit): Long? = when (hit) {
        is Hit.Bar -> hit.id
        is Hit.Grip -> hit.id
        is Hit.Key -> hit.id
        is Hit.Header -> hit.id
        else -> null
    }

    fun cancelDesktopDrag(): Boolean {
        if (!desktopDragging) return false
        desktopCancel = true
        snapDisabled = false
        cancelAction?.invoke()
        cancelAction = null
        desktopDragging = false
        return true
    }

    var cancelAction: (() -> Unit)? = null
    var desktopDragging by mutableStateOf(false)
    private var desktopAnchor: Long? = null
    fun selectDesktop(id: Long, toggle: Boolean, range: Boolean) {
        val anchor = desktopAnchor ?: state.selectedId ?: MultiSelect.ids.firstOrNull()
        val a = if (range) anchor?.let { rowOf(it) } else null
        if (a == null) desktopAnchor = id
        if (a != null) {
            val b = rowOf(id) ?: return
            MultiSelect.ids = (min(a, b)..max(a, b)).mapNotNull { layerAt(it)?.id }.toSet()
            MultiSelect.active = true
            state.deselectLayer()
        } else if (toggle) {
            val chosen = MultiSelect.ids + listOfNotNull(state.selectedId)
            MultiSelect.ids = if (id in chosen) chosen - id else chosen + id
            MultiSelect.active = MultiSelect.ids.isNotEmpty()
            state.deselectLayer()
        } else if (range || !(MultiSelect.active && id in MultiSelect.ids)) {
            MultiSelect.clear()
            state.selectedId = id
            state.panel = EditorPanel.Tools
        }
    }

    fun rubberSelect(rect: Rect) {
        val time = timeScale()
        val ids = visibleRows().mapNotNull { row ->
            val layer = layerAt(row) ?: return@mapNotNull null
            val clip = Rect(time.x(layer.startTime.toFloat()), rowTop(row), time.x(layer.endTime + 1f), rowTop(row) + rowH)
            layer.id.takeIf { clip.overlaps(rect) && clip.right > headerWidth }
        }.toSet()
        state.deselectLayer()
        MultiSelect.ids = ids
        MultiSelect.active = ids.isNotEmpty()
    }

    fun resizeHeader(x: Float) { headerDp = (x / scale).coerceIn(120f, 320f); DeskTimelineControls.headerDp = headerDp; refit() }
    fun nearHeaderEdge(x: Float) = x <= headerWidth && x >= headerWidth - 5.dp.px

    private val covered: Boolean get() = !wide && !PlatformFeatures.desktop && state.selectedId != null

    private val multiBar: Float get() = if (MultiSelect.active && !PlatformFeatures.desktop) MultiBarHeight.px else 0f

    private val panelTop: Float get() = size.height - PanelHeight.px

    private val rowsBottom: Float
        get() = TimelineMath.rowsBottom(size.height.toFloat(), if (covered) panelTop else null, multiBar)

    val scroll: Float
        get() = scrollPx.coerceIn(0f, TimelineMath.maxScroll(layers.size, rowH, rulerH, rowsBottom))

    fun layerAt(row: Int): Layer? = layers.getOrNull(layers.lastIndex - row)

    fun rowTop(row: Int): Float = rulerH + row * rowH - scroll

    private fun rowAt(y: Float): Int = floor((y - rulerH + scroll) / rowH).toInt()

    fun visibleRows(): IntRange = TimelineMath.visibleRows(layers.size, rowH, rulerH, rowsBottom, scroll)

    fun clipRows(media: Boolean): List<Rect> {
        val time = timeScale()
        val from = headerWidth
        val to = size.width.toFloat()
        return visibleRows().mapNotNull { row ->
            val layer = layerAt(row) ?: return@mapNotNull null
            val kind = kindOf(layer)
            val top = maxOf(rowTop(row), rulerH)
            val bottom = minOf(rowTop(row) + rowH, rowsBottom)
            val left = maxOf(time.x(layer.startTime.toFloat()), from)
            val right = minOf(time.x(layer.endTime + 1f), to)
            if (bottom > top && right > left && (kind == TourFacts.MEDIA || !media && kind == TourFacts.TEXT)) Rect(left, top, right, bottom) else null
        }
    }

    fun stripWindow(): StripWindow {
        val time = timeScale()
        return StripWindow(visibleRows(), floor(time.time(0f) / STRIP_WINDOW_MS) * STRIP_WINDOW_MS,
            ceil(time.time(size.width.toFloat()) / STRIP_WINDOW_MS) * STRIP_WINDOW_MS)
    }

    private val keyFrames = HashMap<Pair<Long, KeyScope?>, List<Int>>()
    private val keyFrameNodes = HashMap<Pair<Long, KeyScope?>, app.absentmotion.model.XmlNode>()

    private fun scopeOf(layer: Layer): KeyScope? = if (layer.id == state.selectedId) state.keyScope else null

    private var keyFramesFps = 0

    fun keyFrames(layer: Layer): List<Int> {
        val fps = state.scene.fps
        if (keyFramesFps != fps || keyFrames.size > state.scene.layers.size * 4 + 16) {
            keyFrames.clear()
            keyFrameNodes.clear()
            keyFramesFps = fps
        }
        val scope = scopeOf(layer)
        val key = layer.id to scope
        val cached = keyFrames[key]
        if (cached != null && keyFrameNodes[key] === layer.node) return cached
        return layer.keyframeTimes(scope).map { state.scene.frameAt(it) }.distinct().also {
            keyFrames[key] = it
            keyFrameNodes[key] = layer.node
        }
    }

    private var stepsFps = 0
    private var stepsZoom = 0f
    private var stepsCache: DeskRuler.Steps? = null
    private var labelFps = 0
    private var labelMajor = 0
    private val rulerLabels = HashMap<Int, String>()

    fun rulerSteps(): DeskRuler.Steps {
        val cached = stepsCache
        if (cached != null && stepsFps == fps && stepsZoom == dpPerSecond) return cached
        stepsFps = fps
        stepsZoom = dpPerSecond
        return DeskRuler.steps(fps, dpPerSecond / fps).also { stepsCache = it }
    }

    fun rulerLabel(frame: Int, major: Int): String {
        if (labelFps != fps || labelMajor != major || rulerLabels.size > 512) {
            rulerLabels.clear()
            labelFps = fps
            labelMajor = major
        }
        return rulerLabels.getOrPut(frame) { DeskRuler.label(frame, fps, major) }
    }

    private var scrollBeforeSelect: Float? = null

    fun revealSelected() {
        val id = state.selectedId
        if (id == null) {
            scrollBeforeSelect?.takeUnless { MultiSelect.active }?.let { scrollPx = it }
            scrollBeforeSelect = null
            return
        }
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return
        if (scrollBeforeSelect == null) scrollBeforeSelect = scrollPx
        scrollPx = TimelineMath.centredScroll(layers.lastIndex - index, rowH, rulerH, rowsBottom)
    }

    fun isCovered(at: Offset): Boolean = (covered && at.y >= panelTop) || (multiBar > 0f && at.y >= size.height - multiBar)

    fun dismissZone(at: Offset): Boolean = state.addOpen && TimelineMath.aboveAddMenu(at.y, size.height.toFloat(), AddPopupHeight.px, rulerH)

    fun dismissAddMenu(): Boolean {
        if (!state.addOpen) return false
        state.addOpen = false
        return true
    }

    fun hitAt(at: Offset): Hit {
        if (at.y < rulerH) {
            if (PlatformFeatures.desktop && at.x < headerWidth) return Hit.Corner
            val origin = timeScale().originX
            if (PlatformFeatures.desktop && origin < headerWidth) return Hit.Ruler
            val centre = (origin + PlayheadWidth.px / 2).let { if (PlatformFeatures.desktop) maxOf(it, headerWidth + PillInset.px + PillWidth.px / 2) else it }
            return if (at.y >= ReadoutTop.px && abs(at.x - centre) <= ReadoutHalfWidth.px) Hit.Readout else Hit.Ruler
        }
        val layer = layerAt(rowAt(at.y)) ?: return Hit.Empty
        val time = timeScale()
        val left = time.x(layer.startTime.toFloat())
        val right = time.x(layer.endTime + 1f)
        val selected = layer.id == state.selectedId
        val gripped = selected || !PlatformFeatures.desktop && layer.id in MultiSelect.ids
        val key = if ((selected || PlatformFeatures.desktop) && at.x in left..right) keyNear(layer, at.x, time) else null
        val zone = edgeZone(left, right)
        val keyX = if (PlatformFeatures.desktop && key != null) time.x(state.scene.frameStart(key).toFloat()) else null
        val startReach = if (keyX != null && abs(keyX - left) <= zone) min(zone, GripStub.px) else zone
        val endReach = if (keyX != null && abs(keyX - right) <= zone) min(zone, GripStub.px) else zone
        return when {
            at.x < EyeZone.px -> Hit.Eye(layer.id)
            at.x < headerWidth -> Hit.Header(layer.id)
            PlatformFeatures.desktop && at.x - left in -zone..startReach -> Hit.Grip(layer.id, start = true)
            PlatformFeatures.desktop && right - at.x in -zone..endReach -> Hit.Grip(layer.id, start = false)
            key != null -> Hit.Key(layer.id, key)
            !PlatformFeatures.desktop && gripped && at.x in left - GripZone.px..left -> Hit.Grip(layer.id, start = true)
            !PlatformFeatures.desktop && gripped && at.x in right..right + GripZone.px -> Hit.Grip(layer.id, start = false)
            !PlatformFeatures.desktop && !gripped && at.x >= size.width - HandleZone.px -> Hit.Handle(layer.id)
            at.x in left..right -> Hit.Bar(layer.id)
            else -> Hit.Row(layer.id)
        }
    }

    private fun edgeZone(left: Float, right: Float) = min(6.dp.px, (right - left) / 3f)

    fun tap(hit: Hit) {
        when (hit) {
            is Hit.Eye -> if (MultiSelect.active) MultiSelect.toggle(hit.id) else state.toggleHidden(hit.id)
            is Hit.Header -> select(hit.id)
            is Hit.Bar -> select(hit.id)
            is Hit.Key -> select(hit.id)
            Hit.Readout -> state.toggleBookmark()
            Hit.Empty, Hit.Ruler, is Hit.Row -> state.clearSelection()
            Hit.Corner, is Hit.Grip, is Hit.Handle -> Unit
        }
    }

    private fun select(id: Long) {
        if (MultiSelect.active) MultiSelect.toggle(id) else {
            state.selectedId = id
            state.panel = EditorPanel.Tools
        }
    }

    private fun keyNear(layer: Layer, x: Float, time: TimeScale): Int? {
        val scene = state.scene
        fun distance(frame: Int) = abs(time.x(scene.frameStart(frame).toFloat()) - x)
        return layer.keyframeTimes(scopeOf(layer)).map { scene.frameAt(it) }.minByOrNull(::distance)?.takeIf { distance(it) <= KeyGrab.px }
    }

    fun grabKey(hit: Hit.Key, scope: KeyScope? = if (hit.id == state.selectedId) state.keyScope else null) {
        state.playing = false
        keyLayer = state.scene.layer(hit.id)
        keyScope = scope
        keyFrom = hit.frame
        keyFrame = hit.frame
        keyMs = TimelineMath.frameTimeMs(hit.frame, fps)
        keyId = hit.id
        snapTargets = TimelineMath.snapTargets(state.scene, hit.id, state.frame)
    }

    private fun snapNear(ms: Float): Int? = if (PlatformFeatures.desktop && snapDisabled) null else TimelineMath.snapFrame(ms, snapTargets, fps, SnapZone.px / timeScale().pxPerMs)

    private fun showSnap(snap: Int?, landed: Int) {
        val shown = snap?.takeIf { it == landed }
        if (shown != null && shown != snapAt) onTick()
        snapAt = shown
    }

    private fun keyBy(dx: Float) {
        val original = keyLayer ?: return
        val scene = state.scene
        keyMs += dx / timeScale().pxPerMs
        val snap = snapNear(keyMs)
        val frame = (snap ?: TimelineMath.nearestFrame(keyMs, fps)).coerceIn(scene.frameAt(original.startTime), scene.frameAt(original.endTime))
        showSnap(snap, frame)
        if (frame == keyFrame) return
        keyFrame = frame
        state.edit(KEY_MOVE) { s -> s.update(original.id) { original.withKeyframesMoved(keyFrom, frame, s, keyScope) } }
    }

    private fun dropTrim(key: String) {
        val base = trimBase ?: return
        if ((listOf(base) + trimGroup).all { state.scene.layer(it.id)?.node == it.node }) state.cancelGesture(key)
    }

    private fun dropKey(commit: Boolean) {
        val original = keyLayer
        if (!commit || original == null || state.scene.layer(original.id)?.node == original.node) state.cancelGesture(KEY_MOVE)
        keyLayer = null
        keyId = null
    }

    fun longPress(hit: Hit, y: Float, slop: Float): Boolean {
        val id = when (hit) { is Hit.Eye -> hit.id; is Hit.Header -> hit.id; else -> return false }
        state.deselectLayer()
        if (MultiSelect.active) MultiSelect.toggle(id) else {
            MultiSelect.ids = setOf(id)
            MultiSelect.active = true
        }
        selectAnchor = id
        selectBase = MultiSelect.ids
        selectAdd = id in selectBase
        selectStart = y
        selectFinger = y
        selectSlop = slop
        selectPush = true
        selectArmed = false
        selectRows = 1
        liftJob?.cancel()
        liftJob = scope.launch { liftLoop() }
        return true
    }

    fun selectDrag(y: Float, pushing: Boolean) {
        selectFinger = y
        selectPush = pushing
        if (abs(y - selectStart) > selectSlop) selectArmed = true
        selectUpdate()
    }

    fun endSelect() {
        selectAnchor = null
        liftJob?.cancel()
    }

    private fun selectUpdate() {
        val anchor = rowOf(selectAnchor ?: return) ?: return
        val finger = if (selectArmed) RowDrag.fingerRow(selectFinger, rowH, rulerH, rowsBottom, scroll, layers.size) else anchor
        val rows = abs(finger - anchor) + 1
        if (rows > selectRows) onTick()
        selectRows = rows
        MultiSelect.ids = RowDrag.slideSelection(selectBase, layers.asReversed().map { it.id }, anchor, finger, selectAdd)
    }

    fun beginDrag(hit: Hit, moved: Offset): Drag {
        return when {
            hit is Hit.Grip -> {
                state.playing = false
                val layer = layers.firstOrNull { it.id == hit.id }
                dragId = hit.id
                trimBase = layer
                trimScene = state.scene
                trimGroup = if (MultiSelect.active && hit.id in MultiSelect.ids) layers.filter { it.id in MultiSelect.ids && it.id != hit.id } else emptyList()
                edgeMs = (if (hit.start) layer?.startTime else layer?.endTime?.plus(1))?.toFloat() ?: 0f
                snapTargets = TimelineMath.snapTargets(state.scene, trimGroup.map { it.id }.toSet() + hit.id, state.frame)
                if (hit.start) Drag.TrimStart else Drag.TrimEnd
            }
            hit is Hit.Handle -> {
                dragId = hit.id
                Drag.Reorder
            }
            abs(moved.x) > abs(moved.y) -> {
                state.playing = false
                Drag.Scrub
            }
            else -> Drag.Scroll
        }
    }

    fun dragBy(drag: Drag, delta: Offset, at: Offset) {
        when (drag) {
            Drag.Scrub -> { state.transitionButtonDismissed = false; scrubBy(delta.x) }
            Drag.Scroll -> scrollBy(delta.y)
            Drag.TrimStart, Drag.TrimEnd -> {
                trimBy(delta.x, start = drag == Drag.TrimStart)
                if (PlatformFeatures.desktop) autoEdge(at.x, drag == Drag.TrimStart)
            }
            Drag.Reorder -> reorderTo(at.y)
            Drag.Key -> keyBy(delta.x)
            Drag.Pinch, Drag.Spent, Drag.Lift, Drag.Select -> Unit
        }
    }

    fun endDrag(drag: Drag?, velocity: Velocity, lifted: Boolean = true) {
        autoStop()
        limits = emptyMap()
        snapAt = null
        if (drag == Drag.Lift) drop()
        if (drag == Drag.Lift) dropMove(lifted)
        if (drag == Drag.Select) endSelect()
        if (drag == Drag.Key) dropKey(lifted)
        if (drag == Drag.TrimStart || drag == Drag.TrimEnd) {
            val key = if (drag == Drag.TrimStart) "trim-start" else "trim-end"
            if (PlatformFeatures.desktop && !lifted) state.cancelGesture(key) else dropTrim(key)
        }
        if (drag?.edit == true) state.endGesture()
        motion = scope.launch {
            if (drag == Drag.Scroll) launch { fling(velocity.y, ::scrollBy) }
            if (drag == Drag.Scrub) fling(velocity.x) { state.frame == scrubFrame && scrubBy(it) }
            settle()
        }
    }

    fun stopMotion() {
        motion?.cancel()
    }

    fun followPlayback() {
        stopMotion()
        scrubMs = null
    }

    fun zoomBy(factor: Float, anchor: Float = timeScale().originX) {
        if (PlatformFeatures.desktop) {
            val at = anchor.coerceIn(headerWidth, max(size.width.toFloat(), headerWidth))
            val before = timeScale().time(at)
            val far = if (dpPerSecond >= fitZoom) 1f else (ln(dpPerSecond / minZoom) / ln(fitZoom / minZoom)).coerceIn(0f, 1f)
            state.timelineZoom = (dpPerSecond * factor.pow(0.3f + 0.7f * far)).coerceIn(minZoom, maxZoom)
            followFit = state.timelineZoom >= fitZoom * 0.999f
            setViewStart(before - (at - headerWidth) / timeScale().pxPerMs)
        } else state.timelineZoom = (dpPerSecond * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun scrubBy(dx: Float): Boolean {
        val time = timeScale()
        val wanted = time.nowMs - dx / time.pxPerMs
        val to = wanted.coerceIn(0f, TimelineMath.frameTimeMs(state.scene.lastFrame, fps))
        scrubFrame = TimelineMath.nearestFrame(to, fps)
        scrubMs = to
        state.frame = scrubFrame
        return to == wanted
    }

    private fun scrollBy(dy: Float): Boolean {
        val wanted = scroll - dy
        scrollPx = wanted
        return scroll == wanted
    }

    fun finishWheelScrub() { motion = scope.launch { kotlinx.coroutines.delay(120); settle() } }

    private suspend fun settle() {
        val from = scrubMs ?: return
        if (state.frame == scrubFrame) {
            animate(from, TimelineMath.frameTimeMs(scrubFrame, fps), animationSpec = tween(120)) { value, _ -> scrubMs = value }
        }
        scrubMs = null
    }

    private suspend fun fling(velocity: Float, move: (Float) -> Boolean) {
        var last = 0f
        AnimationState(0f, velocity).animateDecay(decay) {
            if (!move(value - last)) cancelAnimation()
            last = value
        }
    }

    private fun trimBy(dx: Float, start: Boolean) {
        edgeMs += dx / timeScale().pxPerMs
        val shown = state.scene
        if (shown.layer(dragId) == null) return
        val base = trimBase ?: return
        val from = trimScene ?: return
        val snap = snapNear(edgeMs)
        val bases = listOf(base) + trimGroup
        val trims = TimelineMath.trimmedEdges(from, base, trimGroup, start, snap?.let { TimelineMath.frameTimeMs(it, fps) } ?: edgeMs)
        val changed = bases.indices.filter { i -> shown.layer(bases[i].id)?.let { trims[i].time != if (start) it.startTime else it.endTime } == true }
        if (changed.isNotEmpty()) state.edit(if (start) "trim-start" else "trim-end") { s -> changed.fold(s) { acc, i -> acc.withClip(bases[i].id, trims[i].layer, from) } }
        val hits = bases.indices.filter { trims[it].limited }.associate { bases[it].id to start }
        if (hits.isNotEmpty() && limits.isEmpty()) onTick()
        limits = hits
        showSnap(snap.takeUnless { trims[0].limited }, trims[0].edge)
    }

    private var autoX: Float? = null
    private var autoTrim: Boolean? = null
    private var autoJob: Job? = null
    private var panBias = 0f

    private fun autoEdge(x: Float, trimStart: Boolean?) {
        autoX = x
        autoTrim = trimStart
        if (autoJob?.isActive != true) autoJob = scope.launch { autoLoop() }
    }

    private fun autoStop() {
        autoX = null
        panBias = 0f
    }

    private suspend fun autoLoop() {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val step = min((now - last) / 1e9f, 0.05f)
            last = now
            val x = autoX ?: break
            val zone = 48.dp.px
            val over = when {
                x > size.width - zone -> (x - (size.width - zone)) / zone
                x < headerWidth + zone -> -(headerWidth + zone - x) / zone
                else -> 0f
            }.coerceIn(-1.5f, 1.5f)
            if (over == 0f) continue
            val before = viewStartMs
            pan(over * 700.dp.px * step)
            val moved = viewStartMs - before
            if (moved == 0f) continue
            val trim = autoTrim
            if (trim != null) {
                edgeMs += moved
                trimBy(0f, trim)
            } else {
                panBias += moved
                moveApply()
            }
        }
    }

    private fun moveBegin(id: Long): Boolean {
        panBias = 0f
        val layer = state.scene.layer(id) ?: return false
        moveLayer = layer
        moveGroup = if (MultiSelect.active && id in MultiSelect.ids) state.scene.layers.filter { it.id in MultiSelect.ids && it.id != id } else emptyList()
        moveGroupIds = moveGroup.map { it.id }.toSet()
        moveBase = state.scene
        moveId = id
        moveDx = 0f
        moveSlide = 0f
        moveArmed = false
        snapTargets = TimelineMath.snapTargets(state.scene, moveGroupIds + id, state.frame)
        return true
    }

    private fun moveApply() {
        val original = moveLayer ?: return
        state.playing = false
        val scene = state.scene
        val pxPerMs = timeScale().pxPerMs
        val wanted = original.startTime + moveDx / pxPerMs + panBias
        val spanFrames = scene.lastShownFrame(original.endTime) + 1 - scene.firstShownFrame(original.startTime)
        val spanMs = (original.endTime + 1 - original.startTime).toFloat()
        val found = TimelineMath.moveTarget(wanted, spanMs, spanFrames, if (PlatformFeatures.desktop && snapDisabled) emptySet() else snapTargets, fps, SnapZone.px / pxPerMs)
        val target = TimelineMath.groupTarget(scene, original, moveGroup, found)
        val start = TimelineMath.movedStart(scene, original, target.frame)
        showSnap(target.snap, target.snap ?: target.frame)
        moveSlide = if (target.snap != null || target != found || wanted <= 0f) 0f else (wanted - start) * pxPerMs
        val group = moveGroup
        if (scene.layer(original.id)?.startTime != start) state.edit(MOVE_EDIT) { TimelineMath.movedClips(it, original, group, target.frame) }
    }

    private fun dropMove(commit: Boolean) {
        if (!commit || state.scene == moveBase) state.cancelGesture(MOVE_EDIT)
        moveId = null
        moveLayer = null
        moveGroup = emptyList()
        moveGroupIds = emptySet()
        moveBase = null
        moveSlide = 0f
    }

    fun timeFor(id: Long, time: TimeScale) = if (id == moveId || id in moveGroupIds) time.slid(moveSlide) else time

    private fun reorderTo(y: Float) {
        val layers = layers
        if (layers.isEmpty()) return
        val index = layers.lastIndex - rowAt(y).coerceIn(0, layers.lastIndex)
        val id = dragId
        if (layers[index].id != id) state.edit("reorder") { it.reorder(id, index) }
    }

    fun rowOf(id: Long): Int? = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { layers.lastIndex - it }

    private fun progress(ms: Long, total: Int) = Motion.EaseOut.transform((ms / total.toFloat()).coerceIn(0f, 1f))

    val liftAmount: Float
        get() {
            val now = frameMs
            return if (dropping) dropFrom * (1 - progress(now - dropAt, DROP_MS)) else progress(now - liftAt, LIFT_MS)
        }

    fun shownTop(row: Int, id: Long): Float {
        val now = frameMs
        val top = rowTop(row)
        if (id == liftId) return if (dropping) liftTop + (top - liftTop) * progress(now - dropAt, DROP_MS) else liftTop
        val slide = slides[id] ?: return top
        return top + slide.from * (1 - progress(now - slide.at, SLIDE_MS)) * rowH
    }

    fun lift(id: Long, at: Offset, slop: Float, moves: Boolean): Boolean {
        val row = rowOf(id) ?: return false
        if (!moveBegin(id)) return false
        liftMoves = moves
        liftStartX = at.x
        liftJob?.cancel()
        liftAt = SystemClock.uptimeMillis()
        liftGrab = at.y - rowTop(row)
        liftFinger = at.y
        liftStart = at.y
        liftSlop = slop
        liftArmed = false
        liftPush = true
        dropping = false
        liftTop = rowTop(row)
        liftId = id
        liftJob = scope.launch { liftLoop() }
        liftUpdate()
        return true
    }

    fun liftDrag(at: Offset, pushing: Boolean) {
        liftFinger = at.y
        liftPush = pushing
        if (abs(at.y - liftStart) > liftSlop) liftArmed = true
        if (PlatformFeatures.desktop && !liftArmed) liftFinger = liftStart
        if (liftMoves) {
            if (abs(at.x - liftStartX) > liftSlop) moveArmed = true
            if (moveArmed) {
                moveDx = at.x - liftStartX
                moveApply()
                if (PlatformFeatures.desktop) autoEdge(at.x, null)
            }
        }
        liftUpdate()
    }

    fun grabScroll(dy: Float) {
        scrollBy(dy)
    }

    fun liftScroll(dy: Float) {
        scrollBy(dy)
        liftUpdate()
        selectUpdate()
    }

    private fun drop() {
        if (liftId == null || dropping) return
        dropFrom = liftAmount
        dropAt = SystemClock.uptimeMillis()
        dropping = true
    }

    private fun liftUpdate() {
        val id = liftId ?: return
        if (dropping) return
        val layers = layers
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return
        liftTop = RowDrag.clampTop(liftFinger - liftGrab, rowH, rulerH, rowsBottom, scroll, layers.size)
        val from = layers.lastIndex - index
        val to = RowDrag.slot(liftTop, rowH, rulerH, scroll, layers.size)
        if (to == from) return
        val now = SystemClock.uptimeMillis()
        for ((i, layer) in layers.withIndex()) {
            val shift = RowDrag.shift(layers.lastIndex - i, from, to)
            if (shift == 0) continue
            val left = slides[layer.id]?.let { it.from * (1 - progress(now - it.at, SLIDE_MS)) } ?: 0f
            slides[layer.id] = Slide(left - shift, now)
        }
        state.edit(MOVE_EDIT) { it.reorder(id, layers.lastIndex - to) }
        onTick()
    }

    private fun edgeSpeed(y: Float) = RowDrag.edgeSpeed(y, rulerH, rowsBottom, PushZone.px, PUSH_SLOWEST_ROWS * rowH, PUSH_FASTEST_ROWS * rowH)

    private suspend fun liftLoop() {
        val me = coroutineContext.job
        var last = SystemClock.uptimeMillis()
        try {
            while (true) {
                val now = withFrameNanos { SystemClock.uptimeMillis() }
                val step = min((now - last) / 1000f, PUSH_MAX_STEP_S)
                last = now
                frameMs = now
                slides.values.removeAll { now - it.at >= SLIDE_MS }
                if (dropping) {
                    if (now - dropAt >= DROP_MS && slides.isEmpty()) break
                } else if (selectAnchor != null) {
                    val speed = RowDrag.toward(edgeSpeed(selectFinger), selectFinger, selectStart, selectSlop)
                    if (selectPush && speed != 0f) {
                        scrollBy(-speed * step)
                        selectUpdate()
                    }
                } else if (liftArmed && liftPush) {
                    val speed = edgeSpeed(liftFinger)
                    if (speed != 0f) {
                        scrollBy(-speed * step)
                        liftUpdate()
                    }
                }
            }
        } finally {
            if (liftJob === me) {
                liftId = null
                dropping = false
                slides.clear()
            }
        }
    }
}

private val KeyFaint = DpSize(6.4.dp, 9.6.dp)
private val KeyOther = DpSize(9.25.dp, 14.6.dp)
private val KeyHere = DpSize(12.1.dp, 18.5.dp)
private val KeyGrab = 14.dp
private val GripStub = 2.5.dp

private val MacOs = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private suspend fun PointerInputScope.desktopTimelineGestures(view: TimelineView) {
    awaitPointerEventScope {
        var down: Offset? = null
        var hit: Hit? = null
        var drag: Drag? = null
        var resizing = false
        var rubbering = false
        var moved = false
        var previous = Offset.Zero
        var lastClickId: Long? = null
        var lastHeaderId: Long? = null
        var lastClickTime = 0L
        var grab: Offset? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val at = change.position
            val modifiers = event.keyboardModifiers
            val command = if (MacOs) modifiers.isMetaPressed else modifiers.isCtrlPressed
            val plain = !command && !modifiers.isShiftPressed
            when (event.type) {
                PointerEventType.Scroll -> {
                    view.wheel(change.scrollDelta, at, modifiers.isCtrlPressed || modifiers.isMetaPressed, modifiers.isShiftPressed)
                    change.consume()
                    continue
                }
                PointerEventType.Exit -> if (down == null) view.hover(null)
                PointerEventType.Enter, PointerEventType.Move -> if (down == null) view.hover(at)
                else -> Unit
            }
            if (grab != null) {
                if (event.buttons.isTertiaryPressed) {
                    val last = grab
                    view.pan(last.x - at.x)
                    view.grabScroll(at.y - last.y)
                    grab = at
                } else grab = null
                change.consume()
                continue
            }
            if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed && down == null) {
                view.stopMotion()
                grab = at
                change.consume()
                continue
            }
            if (event.type == PointerEventType.Press && (event.buttons.isSecondaryPressed || MacOs && modifiers.isCtrlPressed) && down == null) {
                val id = view.clipId(view.hitAt(at))
                if (id != null) view.selectDesktop(id, false, false)
                view.menuId = id
                view.menuAt = at
                change.consume()
                continue
            }
            if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && down == null && !view.isCovered(at)) {
                view.stopMotion()
                down = at
                previous = at
                hit = view.hitAt(at)
                moved = false
                resizing = view.nearHeaderEdge(at.x)
                view.resizingHeader = resizing
                rubbering = false
                view.desktopCancel = false
                view.desktopDragging = true
                if (!resizing) when (val h = hit) {
                    is Hit.Eye -> view.state.toggleHidden(h.id)
                    is Hit.Grip -> { view.selectDesktop(h.id, false, false); drag = view.beginDrag(h, Offset.Zero) }
                    is Hit.Key -> {
                        val scope = if (h.id == view.state.selectedId) view.state.keyScope else null
                        view.selectDesktop(h.id, false, false)
                        view.grabKey(h, scope)
                        drag = Drag.Key
                    }
                    is Hit.Bar -> {
                        if (plain) { view.selectDesktop(h.id, false, false); if (view.lift(h.id, at, 4.dp.toPx(), true)) drag = Drag.Lift }
                        else view.selectDesktop(h.id, command, modifiers.isShiftPressed)
                    }
                    is Hit.Header -> {
                        view.selectDesktop(h.id, command, modifiers.isShiftPressed)
                        if (plain && view.lift(h.id, at, 4.dp.toPx(), false)) drag = Drag.Lift
                    }
                    Hit.Ruler -> { view.state.transitionButtonDismissed = false; view.seek(at.x) }
                    else -> Unit
                }
                view.cancelAction = { view.endDrag(drag, Velocity.Zero, false); view.rubber = null; drag = null }
                change.consume()
                continue
            }
            val start = down ?: continue
            if (view.desktopCancel) {
                if (!change.pressed) { down = null; hit = null; view.desktopCancel = false; view.resizingHeader = false }
                change.consume()
                continue
            }
            if (change.pressed) {
                if ((at - start).getDistance() > 2f) moved = true
                view.snapDisabled = modifiers.isAltPressed
                when {
                    resizing -> view.resizeHeader(at.x)
                    drag == Drag.Lift -> view.liftDrag(at, true)
                    drag != null -> drag?.let { view.dragBy(it, at - previous, at) }
                    hit == Hit.Ruler || hit == Hit.Readout && moved -> view.seek(at.x)
                    moved && (hit == Hit.Empty || hit is Hit.Row) -> {
                        rubbering = true
                        val rect = Rect(min(start.x, at.x), min(start.y, at.y), max(start.x, at.x), max(start.y, at.y))
                        view.rubber = rect
                        view.rubberSelect(rect)
                    }
                }
                previous = at
                change.consume()
            } else {
                if (!moved && !resizing && !rubbering) when (val h = hit) {
                    is Hit.Bar -> {
                        if (plain && MultiSelect.active) { MultiSelect.clear(); view.state.selectedId = h.id; view.state.panel = EditorPanel.Tools }
                        if (plain && lastClickId == h.id && change.uptimeMillis - lastClickTime <= viewConfiguration.doubleTapTimeoutMillis) {
                            val layer = view.state.scene.layer(h.id)
                            if (layer?.isPlainGroup == true) view.state.enterGroup(h.id)
                            else {
                                view.state.selectedId = h.id
                                MultiSelect.clear()
                                view.state.panel = if (layer?.kind == "text") EditorPanel.EditText else EditorPanel.Tools
                            }
                            lastClickId = null
                        } else { lastClickId = h.id; lastHeaderId = null; lastClickTime = change.uptimeMillis }
                    }
                    is Hit.Header -> {
                        if (plain && MultiSelect.active) { MultiSelect.clear(); view.state.selectedId = h.id; view.state.panel = EditorPanel.Tools }
                        if (lastHeaderId == h.id && change.uptimeMillis - lastClickTime <= viewConfiguration.doubleTapTimeoutMillis) {
                            if (plain && view.state.scene.layer(h.id)?.isPlainGroup == true) view.state.enterGroup(h.id)
                            lastHeaderId = null
                        } else { lastHeaderId = h.id; lastClickId = null; lastClickTime = change.uptimeMillis }
                    }
                    Hit.Readout -> view.tap(h)
                    Hit.Empty, is Hit.Row -> view.state.clearSelection()
                    else -> Unit
                }
                view.endDrag(drag, Velocity.Zero, true)
                view.rubber = null
                view.desktopDragging = false
                view.resizingHeader = false
                view.cancelAction = null
                view.snapDisabled = false
                down = null
                hit = null
                drag = null
                change.consume()
            }
        }
    }
}

private suspend fun PointerInputScope.timelineGestures(view: TimelineView, onLongPress: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown()
    view.stopMotion()
    if (view.isCovered(down.position)) return@awaitEachGesture
    val hit = view.hitAt(down.position)
    val tracker = VelocityTracker().apply { addPointerInputChange(down) }
    var drag: Drag? = null
    var lifted = false
    var held = false
    var last: PointerInputChange? = null
    var finger = down.position
    var second: PointerId? = null
    val dismissing = view.dismissZone(down.position)
    val holdable = !dismissing && (hit is Hit.Eye || hit is Hit.Header || hit is Hit.Row || hit is Hit.Key || hit is Hit.Bar)
    val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
    try {
        while (true) {
            val event = if (holdable && drag == null && !held) {
                withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (event == null) {
                when {
                    hit is Hit.Key -> {
                        view.grabKey(hit)
                        drag = Drag.Key
                    }
                    hit is Hit.Row && view.lift(hit.id, finger, viewConfiguration.touchSlop, moves = false) -> drag = Drag.Lift
                    hit is Hit.Bar && view.lift(hit.id, finger, viewConfiguration.touchSlop, moves = true) -> drag = Drag.Lift
                    view.longPress(hit, finger.y, viewConfiguration.touchSlop) -> drag = Drag.Select
                    else -> held = true
                }
                onLongPress()
                continue
            }
            val fingers = event.changes.count { it.pressed }
            val first = event.changes.firstOrNull { it.id == down.id }
            last = first
            if (first != null) {
                tracker.addPointerInputChange(first)
                finger = first.position
            }
            when {
                fingers == 0 -> break
                drag == Drag.Lift || drag == Drag.Select -> {
                    if (first == null || !first.pressed) break
                    for (change in event.changes) {
                        if (change.id == down.id) continue
                        if (second == null && change.changedToDown() && change.position.y >= view.rulerH) second = change.id
                        if (change.id != second) continue
                        if (change.pressed) view.liftScroll(change.position.y - change.previousPosition.y) else second = null
                    }
                    if (drag == Drag.Lift) view.liftDrag(first.position, pushing = second == null)
                    else view.selectDrag(first.position.y, pushing = second == null)
                    event.changes.forEach { it.consume() }
                }
                fingers > 1 && drag == Drag.Key -> {
                    view.endDrag(Drag.Key, Velocity.Zero, lifted = false)
                    drag = Drag.Spent
                }
                fingers > 1 && drag?.edit != true -> {
                    drag = Drag.Pinch
                    view.zoomBy(event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
                drag == Drag.Pinch || drag == Drag.Spent -> drag = Drag.Spent
                first == null || !first.pressed -> break
                drag == null -> {
                    val moved = first.position - down.position
                    if (moved.getDistance() > viewConfiguration.touchSlop) drag = view.beginDrag(hit, moved)
                }
                else -> {
                    view.dragBy(drag, first.position - first.previousPosition, first.position)
                    first.consume()
                }
            }
        }
        lifted = TimelineMath.released(last)
    } finally {
        if (lifted && (drag == null && !held || drag == Drag.Lift && hit is Hit.Bar && !view.liftMoved)) {
            if (dismissing) view.dismissAddMenu() else view.tap(hit)
        }
        view.endDrag(drag, if (lifted) tracker.calculateVelocity() else Velocity.Zero, lifted)
    }
}

private fun DrawScope.drawTimeline(view: TimelineView, measurer: TextMeasurer, styles: TimelineStyles, eye: Painter, eyeOff: Painter, outline: Path, pictures: Map<Long, StripPicture>, proxies: Map<String, Float>, appear: Float = 1f) {
    val state = view.state
    val fps = view.fps
    val time = view.timeScale()
    val step = TimelineMath.tickStep(fps, view.dpPerSecond)
    val desktop = PlatformFeatures.desktop
    if (desktop) drawDeskRuler(view, time, measurer, styles)
    else {
        if (view.layers.isNotEmpty()) drawRuler(time, fps, state.scene.lastFrame, step)
        if (view.layers.isNotEmpty() && (state.scene.isElement || state.inGroup)) drawRetimeMarks(state.scene, time)
    }
    if (view.layers.isNotEmpty() && !state.inGroup) drawCacheBar(state, time, fps, if (desktop) view.headerWidth else 0f, view.rulerH)
    val stripe = TimelineMath.frameTimeMs(2 * step, fps) * time.pxPerMs
    fun paint(row: Int, layer: Layer) {
        val rowTime = view.timeFor(layer.id, time)
        drawRow(layer, layer.id == state.selectedId, view.shownTop(row, layer.id), rowTime, stripe, measurer, styles, if (layer.hidden) eyeOff else eye, outline, state.scene, state.frame, pictures[layer.id], view.keyFrames(layer), appear, view.keyFrame.takeIf { layer.id == view.keyId }, view.limits[layer.id], view.headerWidth, layer.id == view.hoveredId, view.hoveredKey?.takeIf { it.first == layer.id }?.second)
        layer.mediaFill?.takeIf { it.video }?.let { proxies[it.uri] }?.let { done ->
            drawProxyProgress(rowTime.x(layer.startTime.toFloat()), rowTime.x(layer.endTime + 1f), view.shownTop(row, layer.id), done)
        }
    }
    clipRect(top = view.rulerH) {
        for (row in view.visibleRows()) {
            val layer = view.layerAt(row) ?: continue
            if (layer.id == view.liftId) continue
            paint(row, layer)
        }
        if (desktop) {
            view.liftId?.let { id -> view.rowOf(id)?.let { row -> view.layerAt(row)?.let { layer ->
                drawLiftShadow(view.shownTop(row, id), view.rowH)
                paint(row, layer)
            } } }
        }
    }
    if (desktop) drawDeskFrame(view, time)
    view.snapAt?.let { frame ->
        val half = SnapLine.toPx() / 2
        drawRect(SnapColor, Offset(time.x(state.scene.frameStart(frame).toFloat()) - half, view.rulerH), Size(2 * half, size.height - view.rulerH))
    }
    if (desktop) {
        view.rubber?.let { rect ->
            drawRect(Am.Accent.copy(alpha = 0.14f), rect.topLeft, rect.size)
            drawRect(Am.Accent, rect.topLeft, rect.size, style = Stroke(1.dp.toPx()))
        }
        if (DeskDrop.item != null && DeskDrop.at.isSpecified) view.frameAtWindow(DeskDrop.at)?.let { frame ->
            val x = time.x(state.scene.frameStart(frame).toFloat())
            drawLine(Am.Accent, Offset(x, view.rulerH), Offset(x, size.height), 2.dp.toPx())
        }
    }
    val marks = state.scene.bookmarks
    val onBookmark = marks.any { state.scene.frameAt(it) == state.frame }
    if (desktop) {
        clipRect(left = view.headerWidth) { for (ms in marks) drawBookmark(time.x(ms.toFloat()), outline) }
        if (time.originX in view.headerWidth..size.width) drawPlayhead(time.originX, TimelineMath.timecode(state.frame, fps), measurer, styles, outline, onBookmark, view.headerWidth + PillInset.toPx())
    } else {
        for (ms in marks) drawBookmark(time.x(ms.toFloat()), outline)
        drawPlayhead(time.originX, TimelineMath.timecode(state.frame, fps), measurer, styles, outline, onBookmark)
    }
}

private fun DrawScope.drawLiftShadow(top: Float, rowH: Float) {
    val step = LiftShadowStep.toPx()
    for (i in 1..4) {
        val alpha = LIFT_SHADOW_ALPHA * (5 - i)
        drawRect(Color.Black, Offset(0f, top - i * step), Size(size.width, step), alpha = alpha)
        drawRect(Color.Black, Offset(0f, top + rowH + (i - 1) * step), Size(size.width, step), alpha = alpha)
    }
}

private fun DrawScope.drawDeskFrame(view: TimelineView, time: TimeScale) {
    val headerWidth = view.headerWidth
    val end = max(time.x(view.state.scene.totalTime.toFloat()), headerWidth)
    if (end < size.width) drawRect(Am.PreviewBg.copy(alpha = 0.5f), Offset(end, view.rulerH), Size(size.width - end, size.height - view.rulerH))
    val line = 1.dp.toPx()
    drawRect(if (view.resizeHover || view.resizingHeader) Am.Accent else Am.Button, Offset(headerWidth - line, 0f), Size(line, size.height))
    val thick = 4.dp.toPx()
    val gap = 2.dp.toPx()
    val minThumb = 24.dp.toPx()
    val colour = Am.Inactive.copy(alpha = 0.7f)
    val viewH = view.rowsEnd - view.rulerH
    if (view.maxScroll > 0f) {
        val track = viewH - 2 * gap - thick
        val thumb = max(minThumb, track * viewH / (viewH + view.maxScroll))
        drawRoundRect(colour, Offset(size.width - thick - gap, view.rulerH + gap + (track - thumb) * view.scroll / view.maxScroll), Size(thick, thumb), CornerRadius(thick / 2))
    }
    if (view.panSpan < 0.995f) {
        val track = size.width - headerWidth - 2 * gap - thick
        val thumb = max(minThumb, track * view.panSpan)
        drawRoundRect(colour, Offset(headerWidth + gap + (track - thumb) * view.panFraction, size.height - thick - gap), Size(thumb, thick), CornerRadius(thick / 2))
    }
}

private fun DrawScope.drawDeskRuler(view: TimelineView, time: TimeScale, measurer: TextMeasurer, styles: TimelineStyles) {
    val headerWidth = view.headerWidth
    val rulerH = view.rulerH
    val fps = view.fps
    drawRect(Am.Panel, Offset.Zero, Size(headerWidth, size.height))
    drawRect(Am.Line, Offset(headerWidth, rulerH - 1.dp.toPx()), Size(size.width - headerWidth, 1.dp.toPx()))
    if (view.layers.isEmpty()) return
    val steps = view.rulerSteps()
    val first = floor(time.time(headerWidth) * fps / 1000f).toInt().coerceAtLeast(0) / steps.minor * steps.minor
    val last = ceil(time.time(size.width) * fps / 1000f).toInt()
    val width = TickWidth.toPx()
    clipRect(left = headerWidth) {
        var frame = first
        while (frame <= last) {
            val x = time.x(TimelineMath.frameTimeMs(frame, fps))
            if (frame % steps.major == 0) {
                drawRect(RulerMajor, Offset(x, 3.dp.toPx()), Size(width, rulerH - 4.dp.toPx()))
                val text = measurer.measure(view.rulerLabel(frame, steps.major), styles.ruler)
                drawText(text, topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx()))
            } else {
                val height = if (steps.major / steps.minor % 2 == 0 && frame % (steps.major / 2) == 0) 9.dp else 5.dp
                drawRect(RulerMinor, Offset(x, rulerH - 1.dp.toPx() - height.toPx()), Size(width, height.toPx()))
            }
            frame += steps.minor
        }
        if (view.state.scene.isElement || view.state.inGroup) drawRetimeMarks(view.state.scene, time)
    }
}

private fun DrawScope.drawLifted(view: TimelineView, measurer: TextMeasurer, styles: TimelineStyles, eye: Painter, eyeOff: Painter, outline: Path, pictures: Map<Long, StripPicture>, appear: Float) {
    val id = view.liftId ?: return
    val row = view.rowOf(id) ?: return
    val layer = view.layerAt(row) ?: return
    val state = view.state
    val time = view.timeScale()
    val stripe = TimelineMath.frameTimeMs(2 * TimelineMath.tickStep(view.fps, view.dpPerSecond), view.fps) * time.pxPerMs
    val amount = view.liftAmount
    val rowH = view.rowH
    drawRect(Am.TimelineBg, alpha = LIFT_DIM * amount)
    translate(top = -view.rulerH) {
        val top = view.shownTop(row, id)
        val grow = 1 + (LIFT_SCALE - 1) * amount
        scale(grow, grow, Offset(size.width / 2, top + rowH / 2)) {
            val step = LiftShadowStep.toPx()
            for (i in 1..4) drawRect(Color.Black, Offset(-i * step, top - i * step + step), Size(size.width + 2 * i * step, rowH + 2 * i * step), alpha = LIFT_SHADOW_ALPHA * amount)
            drawRect(Am.TimelineBg, Offset(0f, top), Size(size.width, rowH), alpha = amount)
            drawRow(layer, id == state.selectedId, top, view.timeFor(id, time), stripe, measurer, styles, if (layer.hidden) eyeOff else eye, outline, state.scene, state.frame, pictures[id], view.keyFrames(layer), appear, headerWidth = view.headerWidth, hovered = id == view.hoveredId)
        }
    }
}

private fun DrawScope.drawProxyProgress(left: Float, right: Float, top: Float, done: Float) {
    val from = max(left, 0f)
    val to = min(right, size.width)
    if (from >= to) return
    val height = ProxyLineHeight.toPx()
    val y = top + (RowHeight - BarInset).toPx() - height - ProxyLineInset.toPx()
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(from, y), Size(to - from, height))
    drawRect(Am.Accent, Offset(from, y), Size((to - from) * done.coerceIn(0f, 1f), height))
}

private fun DrawScope.drawBookmark(x: Float, outline: Path) {
    if (x < -10.dp.toPx() || x > size.width + 10.dp.toPx()) return
    val colour = BookmarkColor.copy(alpha = BOOKMARK_ALPHA)
    val half = BookmarkLineWidth.toPx() / 2
    drawRect(colour, Offset(x - half, 0f), Size(2 * half, RulerHeight.toPx()))
    outline.rewind()
    if (PlatformFeatures.desktop) {
        val side = 7.dp.toPx()
        val bottom = 11.dp.toPx()
        outline.moveTo(x + half, 1.dp.toPx())
        outline.lineTo(x + half, bottom)
        outline.quadraticTo(x + half - side * 0.85f, bottom, x + half - side, 1.dp.toPx())
    } else {
        outline.moveTo(x + half, BookmarkFlagTop.toPx())
        outline.lineTo(x + half, BookmarkFlagBottom.toPx())
        outline.lineTo(x + half - BookmarkFlagSide.toPx(), BookmarkFlagBottom.toPx())
    }
    outline.close()
    drawPath(outline, colour)
}

private fun DrawScope.drawCacheBar(state: EditorState, time: TimeScale, fps: Int, left: Float, rulerH: Float) {
    PreviewCache.revision
    val runs = PreviewCache.runsFor(state)
    if (runs.isEmpty()) return
    val height = CacheBarHeight.toPx()
    val top = rulerH - height - 1.dp.toPx()
    clipRect(left = left) {
        for (run in runs) {
            val start = time.x(TimelineMath.frameTimeMs(run.first, fps))
            val end = time.x(TimelineMath.frameTimeMs(run.last + 1, fps))
            if (end < left || start > size.width) continue
            drawRect(CacheBarColor, Offset(start, top), Size(end - start, height))
        }
    }
}

private fun DrawScope.drawRuler(time: TimeScale, fps: Int, lastFrame: Int, tickFrames: Int) {
    val width = TickWidth.toPx()
    val firstSecond = floor(time.time(0f) / 1000f).toInt().coerceAtLeast(0)
    val lastSecond = min(floor(time.time(size.width) / 1000f).toInt(), lastFrame / fps)
    for (second in firstSecond..lastSecond) {
        for (i in 0 until fps step tickFrames) {
            val frame = second * fps + i
            if (frame > lastFrame) return
            val height = when {
                i == 0 -> TickSecond
                i * 2 == fps -> TickHalfSecond
                else -> TickShort
            }
            drawRect(TickColor, Offset(time.x(TimelineMath.frameTimeMs(frame, fps)), 0f), Size(width, height.toPx()))
        }
    }
}

private fun DrawScope.drawRetimeMarks(scene: Scene, time: TimeScale) {
    val width = TickWidth.toPx()
    val flag = TickShort.toPx()
    fun mark(ms: Int, direction: Float) {
        val x = time.x(scene.frameTime(scene.frameAt(ms)).toFloat())
        drawLine(RetimeMarkColor, Offset(x, 0f), Offset(x, flag * 2), width)
        drawLine(RetimeMarkColor, Offset(x, 0f), Offset(x + direction * flag / 2, 0f), width)
    }
    if (scene.retimeIn > 0) mark(scene.retimeIn, 1f)
    if (scene.retimeOut > 0) mark(scene.retimeOut, -1f)
}

private fun DrawScope.drawRow(
    layer: Layer, selected: Boolean, top: Float, time: TimeScale, stripe: Float,
    measurer: TextMeasurer, styles: TimelineStyles, eye: Painter, outline: Path, scene: Scene, frame: Int, picture: StripPicture?, keyFrames: List<Int>, appear: Float = 1f, held: Int? = null, limit: Boolean? = null, headerWidth: Float = 0f, hovered: Boolean = false, hoverKey: Int? = null,
) {
    val barTop = top + BarInset.toPx()
    val barBottom = top + (RowHeight - BarInset).toPx()
    val centre = top + RowHeight.toPx() / 2
    val left = time.x(layer.startTime.toFloat())
    val right = time.x(layer.endTime + 1f)

    if (layer.kind == "camera" && layer.id == scene.defaultCameraId) drawRect(CameraBar.copy(alpha = 0.1f), Offset(0f, top), Size(size.width, RowHeight.toPx()))
    if (PlatformFeatures.desktop && (selected || layer.id in MultiSelect.ids)) drawRect(Color.Black, Offset(headerWidth, top), Size(size.width - headerWidth, RowHeight.toPx()), alpha = SelectedRowShade)
    if (layer.kind == "audio") drawAudioBar(outline, layer, left, right, barTop, barBottom, time.pxPerMs)
    else drawBar(outline, left, right, barTop, barBottom, stripe, picture, layer.startTime, time.pxPerMs, layer.kind == "text", layer.kind == "camera")
    layer.labelColour?.let { drawTagCorner(outline, left, barTop, (barBottom - barTop) / 2, Color(it.start)) }
    val mediaLabel = PlatformFeatures.desktop && picture != null
    val labelStyle = if (mediaLabel) styles.mediaLabel else if (layer.kind == "audio") styles.audioLabel else styles.label
    drawLabel(layer.shownName(), measurer, if (PlatformFeatures.desktop) max(left, headerWidth) else left, right, barTop, barBottom, labelStyle, mediaLabel)
    drawKeyframes(keyFrames, scene, selected, frame, time, left, right, centre, outline, appear, held, hoverKey)
    if (PlatformFeatures.desktop) {
        val chosen = selected || layer.id in MultiSelect.ids
        drawDeskClipFrame(left, right, barTop, barBottom, headerWidth, chosen, hovered, limit)
        drawDeskHeader(layer, chosen, hovered, top, headerWidth, eye, measurer, styles, layer.node["clippingMask"] == "true")
    } else {
        if (selected || layer.id in MultiSelect.ids) {
            drawGrip(outline, left, barTop, barBottom, start = true, appear, limit == true)
            drawGrip(outline, right, barTop, barBottom, start = false, appear, limit == false)
        } else {
            drawGrabLines(Offset(size.width - HandleInset.toPx(), centre))
        }
        drawHeader(top, eye, layer.labelColour, layer.id in MultiSelect.ids, layer.node["clippingMask"] == "true")
    }
}

private fun DrawScope.drawDeskClipFrame(left: Float, right: Float, top: Float, bottom: Float, headerWidth: Float, chosen: Boolean, hovered: Boolean, limit: Boolean?) {
    val from = max(left, headerWidth)
    val to = min(right, size.width)
    if (to <= from || !chosen && !hovered) return
    val stroke = (if (chosen) 2.dp else 1.dp).toPx()
    val inset = stroke / 2
    drawRoundRect(if (chosen) Am.Accent else Color.White.copy(alpha = 0.65f), Offset(from + inset, top + inset), Size(to - from - stroke, bottom - top - stroke), CornerRadius(BarRadius.toPx()), style = Stroke(stroke))
    if (!chosen) return
    val width = 3.dp.toPx()
    val height = (bottom - top) * 0.5f
    for (i in 0..1) {
        val start = i == 0
        val edge = if (start) left else right
        if (edge < headerWidth || edge > size.width) continue
        val x = if (start) edge + 3.dp.toPx() else edge - 3.dp.toPx() - width
        drawRoundRect(if (limit == start) LimitColor else Am.OnDark, Offset(x, (top + bottom - height) / 2), Size(width, height), CornerRadius(width / 2))
    }
}

private fun DrawScope.drawDeskHeader(layer: Layer, chosen: Boolean, hovered: Boolean, top: Float, width: Float, eye: Painter, measurer: TextMeasurer, styles: TimelineStyles, clipped: Boolean = false) {
    val height = RowHeight.toPx()
    drawRect(Am.TimelineBg, Offset(0f, top), Size(width, height))
    val pillHeight = height - HeaderInset.toPx() * 2
    val pillTop = Offset(-pillHeight, top + HeaderInset.toPx())
    val trim = if (clipped) DeskClipTrim.toPx() else 0f
    val pill = Size(width - 2.dp.toPx() - trim + pillHeight, pillHeight)
    if (clipped) drawClipHook(width - 2.dp.toPx() - trim + 1.dp.toPx(), top + height / 2)
    val radius = CornerRadius(pillHeight / 2)
    drawRoundRect(HeaderFill, pillTop, pill, radius)
    if (chosen) drawRoundRect(Am.Accent.copy(alpha = 0.28f), pillTop, pill, radius)
    else if (hovered) drawRoundRect(Am.Field, pillTop, pill, radius)
    drawRoundRect(HeaderEdge, pillTop, pill, radius, style = Stroke(0.5.dp.toPx()))
    if (chosen) drawRect(Am.Accent, Offset(0f, pillTop.y), Size(3.dp.toPx(), pillHeight))
    layer.labelColour?.let { drawRect(Color(it.start), Offset(0f, pillTop.y), Size(3.dp.toPx(), pillHeight)) }
    val icon = eye.intrinsicSize
    translate(EyeCentre.toPx() - icon.width / 2, top + (height - icon.height) / 2) { with(eye) { draw(icon) } }
    val left = 56.dp.toPx()
    val room = (width - trim - left - pillHeight / 2 - 4.dp.toPx()).toInt()
    if (room < 1) return
    val text = measurer.measure(layer.shownName(), if (layer.hidden) styles.hiddenHeader else styles.header, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = room))
    drawText(text, topLeft = Offset(left, top + (height - text.size.height) / 2))
}

private fun DrawScope.drawBar(outline: Path, left: Float, right: Float, top: Float, bottom: Float, stripe: Float, picture: StripPicture?, startTime: Int, pxPerMs: Float, text: Boolean = false, camera: Boolean = false) {
    val radius = BarRadius.toPx()
    val from = max(left, -2 * radius)
    val to = min(right, size.width + 2 * radius)
    if (from >= to) return
    drawRect(BarShadow, Offset(from, bottom - radius), Size(to - from, radius + BarShadowHeight.toPx()))
    outline.rewind()
    outline.addRoundRect(RoundRect(from, top, to, bottom, CornerRadius(radius)))
    clipPath(outline) {
        drawRect(if (camera) CameraBar else if (text) TextBar else Am.LayerBar, Offset(from, top), Size(to - from, bottom - top))
        if (picture != null) {
            val tileHeight = (bottom - top).roundToInt().coerceAtLeast(1)
            val tileWidth = (tileHeight.toFloat() * picture.size.width / picture.size.height).roundToInt().coerceAtLeast(1)
            var x = left + tileWidth * floor((from - left) / tileWidth)
            while (x < to) {
                val projectTime = startTime + (x - left) / pxPerMs
                val frame = picture.frames.lastOrNull { it.first <= projectTime + 1 }?.second
                val bitmap = frame ?: picture.bitmap
                drawImage(bitmap, srcOffset = if (frame == null) picture.offset else IntOffset.Zero,
                    srcSize = if (frame == null) picture.size else IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(x.roundToInt(), top.roundToInt()), dstSize = IntSize(tileWidth, tileHeight))
                x += tileWidth
            }
        } else {
            var x = left + stripe * (2 * floor((from - left) / (2 * stripe)) + 1)
            while (x < to) {
                drawRect(if (camera) CameraStripe else if (text) TextStripe else BarStripe, Offset(x, top), Size(stripe, bottom - top))
                x += 2 * stripe
            }
        }
    }
}

private fun DrawScope.drawKeyframes(frames: List<Int>, scene: Scene, selected: Boolean, frame: Int, time: TimeScale, left: Float, right: Float, centre: Float, outline: Path, appear: Float = 1f, held: Int? = null, hoverKey: Int? = null) {
    if (frames.isEmpty()) return
    val grabbed = held?.let { h -> frames.minByOrNull { abs(it - h) } }
    clipRect(left = max(left, 0f), right = min(right, size.width)) {
        for (keyFrame in frames) {
            val x = time.x(scene.frameStart(keyFrame).toFloat())
            if (x < -KeyHere.width.toPx() || x > size.width + KeyHere.width.toPx()) continue
            val here = selected && (keyFrame == frame || keyFrame == grabbed)
            val grow = (if (selected) 0.7f + 0.3f * appear else 1f) * if (PlatformFeatures.desktop && keyFrame == hoverKey) 1.25f else 1f
            val half = (if (here) KeyHere else if (selected) KeyOther else KeyFaint).let { Offset(it.width.toPx() / 2 * grow, it.height.toPx() / 2 * grow) }
            outline.rewind()
            outline.moveTo(x, centre - half.y)
            outline.lineTo(x + half.x, centre)
            outline.lineTo(x, centre + half.y)
            outline.lineTo(x - half.x, centre)
            outline.close()
            if (!selected) {
                drawPath(outline, Color.White.copy(alpha = 0.7f))
                continue
            }
            if (here) drawPath(outline, Am.Accent, style = Stroke(3.55.dp.toPx()))
            drawPath(outline, Color.Black, style = Stroke(1.4.dp.toPx()))
            drawPath(outline, if (keyFrame == grabbed) Am.Accent else Color.White)
        }
    }
}

private fun DrawScope.drawTagCorner(outline: Path, left: Float, top: Float, side: Float, colour: Color) {
    outline.rewind()
    outline.moveTo(left, top + side)
    outline.lineTo(left, top)
    outline.lineTo(left + side, top)
    outline.close()
    drawPath(outline, colour)
}

private fun DrawScope.drawLabel(label: String, measurer: TextMeasurer, left: Float, right: Float, top: Float, bottom: Float, style: TextStyle, backdrop: Boolean = false) {
    val inset = LabelInset.toPx()
    val room = min((right - left - 2 * inset).toInt(), MAX_LABEL_PX)
    if (room < 1 || left + inset > size.width || left + inset + room < 0) return
    val text = measurer.measure(label, style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = room))
    val textTop = (top + bottom - text.size.height) / 2
    if (backdrop) drawRoundRect(Color.Black.copy(alpha = 0.65f), Offset(left + inset - 3.dp.toPx(), textTop), Size(text.size.width + 6.dp.toPx(), text.size.height.toFloat()), CornerRadius(3.dp.toPx()))
    drawText(text, topLeft = Offset(left + inset, textTop))
}

private fun DrawScope.drawGrip(outline: Path, edge: Float, top: Float, bottom: Float, start: Boolean, appear: Float = 1f, limit: Boolean = false) {
    val width = (GripZone - GripGap * 2).toPx()
    val left = if (start) edge - GripGap.toPx() - width else edge + GripGap.toPx()
    if (left > size.width || left + width < 0) return
    val far = CornerRadius((bottom - top) / 2)
    val near = CornerRadius(1.dp.toPx())
    val rect = Rect(left, top, left + width, bottom)
    outline.rewind()
    outline.addRoundRect(
        if (start) RoundRect(rect, topLeft = far, bottomLeft = far, topRight = near, bottomRight = near)
        else RoundRect(rect, topLeft = near, bottomLeft = near, topRight = far, bottomRight = far),
    )
    drawPath(outline, if (limit) LimitColor else GripColor, alpha = appear)

    val centre = rect.center
    val reach = 1.95.dp.toPx() * if (start) 1 else -1
    val rise = 4.1.dp.toPx()
    val tip = Offset(centre.x - reach, centre.y)
    for (side in intArrayOf(-1, 1)) {
        drawLine(Am.TimelineBg, Offset(centre.x + reach, centre.y + side * rise), tip, 1.5.dp.toPx(), StrokeCap.Round, alpha = appear)
    }
}

private fun DrawScope.drawGrabLines(centre: Offset) {
    val half = 4.dp.toPx()
    for (line in -1..1) {
        val y = centre.y + line * 2.85.dp.toPx()
        drawLine(HandleColor, Offset(centre.x - half, y), Offset(centre.x + half, y), 0.7.dp.toPx())
    }
}

private fun DrawScope.drawHeader(top: Float, eye: Painter, tag: LabelColour?, checked: Boolean, clipped: Boolean = false) {
    val height = (RowHeight - HeaderInset * 2).toPx()
    val topLeft = Offset(-height, top + HeaderInset.toPx())
    val trim = if (clipped) ClipTrim.toPx() else 0f
    val pill = Size(HeaderWidth.toPx() + height - trim, height)
    if (clipped) drawClipHook(HeaderWidth.toPx() - trim + 1.dp.toPx(), top + RowHeight.toPx() / 2)
    val radius = CornerRadius(height / 2)
    drawRoundRect(if (checked) Am.Accent else HeaderFill, topLeft, pill, radius)
    drawRoundRect(HeaderEdge, topLeft, pill, radius, style = Stroke(0.5.dp.toPx()))
    if (tag != null) drawRect(Color(tag.start), Offset(0f, topLeft.y + 0.7.dp.toPx()), Size(TagStripWidth.toPx(), height - 1.8.dp.toPx()))
    if (MultiSelect.active) {
        if (checked) {
            val centre = Offset(EyeCentre.toPx(), top + RowHeight.toPx() / 2)
            drawCircle(HeaderFill, 7.5.dp.toPx(), centre)
            val path = Path().apply {
                moveTo(centre.x - 3.5.dp.toPx(), centre.y)
                lineTo(centre.x - 1.dp.toPx(), centre.y + 2.5.dp.toPx())
                lineTo(centre.x + 4.dp.toPx(), centre.y - 3.dp.toPx())
            }
            drawPath(path, Am.Accent, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
        }
        return
    }
    val icon = eye.intrinsicSize
    translate(EyeCentre.toPx() - icon.width / 2, top + (RowHeight.toPx() - icon.height) / 2) {
        with(eye) { draw(icon) }
    }
}

private val hookPath = Path()

private fun DrawScope.drawClipHook(x: Float, y: Float) {
    val bend = 3.dp.toPx()
    val end = x + 8.dp.toPx()
    val tip = y + 11.dp.toPx()
    val head = 3.dp.toPx()
    val hook = hookPath.apply {
        rewind()
        moveTo(x, y)
        lineTo(end - bend, y)
        quadraticTo(end, y, end, y + bend)
        lineTo(end, tip)
        moveTo(end - head, tip - head)
        lineTo(end, tip)
        lineTo(end + head, tip - head)
    }
    drawPath(hook, Color.Black.copy(alpha = 0.45f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(hook, Am.OnDark, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawPlayhead(originX: Float, timecode: String, measurer: TextMeasurer, styles: TimelineStyles, outline: Path, onBookmark: Boolean, minLeft: Float = Float.NEGATIVE_INFINITY) {
    val width = PlayheadWidth.toPx()
    drawRect(if (onBookmark) BookmarkColor else Am.OnDark, Offset(originX, 0f), Size(width, size.height))
    if (onBookmark) {
        val centre = originX + width / 2
        outline.rewind()
        if (PlatformFeatures.desktop) {
            val peak = 5.dp.toPx()
            val tip = 11.dp.toPx()
            outline.moveTo(centre - peak, 0f)
            outline.lineTo(centre + peak, 0f)
            outline.quadraticTo(centre + peak, tip * 0.65f, centre, tip)
            outline.quadraticTo(centre - peak, tip * 0.65f, centre - peak, 0f)
        } else {
            outline.moveTo(centre, 0f)
            outline.lineTo(centre + BookmarkPeakHalf.toPx(), BookmarkFlagBottom.toPx())
            outline.lineTo(centre - BookmarkPeakHalf.toPx(), BookmarkFlagBottom.toPx())
        }
        outline.close()
        drawPath(outline, BookmarkColor)
    }

    val left = maxOf(minLeft, originX + (width - PillWidth.toPx()) / 2)
    val right = left + PillWidth.toPx()
    val top = PillTop.toPx()
    val bottom = top + PillHeight.toPx()
    val notch = PillNotch.toPx()
    val round = (if (PlatformFeatures.desktop) 4.dp else 1.dp).toPx()
    outline.rewind()
    outline.moveTo(left + notch, top)
    outline.lineTo(right - round, top)
    outline.quadraticTo(right, top, right, top + round)
    outline.lineTo(right, bottom - round)
    outline.quadraticTo(right, bottom, right - round, bottom)
    outline.lineTo(left + round, bottom)
    outline.quadraticTo(left, bottom, left, bottom - round)
    outline.lineTo(left, top + notch)
    outline.close()
    drawPath(outline, PillColor)
    if (onBookmark) clipPath(outline) { drawPath(outline, BookmarkColor, style = Stroke(2 * PillOutline.toPx())) }

    val text = measurer.measure(timecode, styles.timecode)
    drawText(text, topLeft = Offset((left + right - text.size.width) / 2, (top + bottom - text.size.height) / 2))
}
