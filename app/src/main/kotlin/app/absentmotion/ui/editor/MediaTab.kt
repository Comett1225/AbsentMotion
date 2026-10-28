package app.absentmotion.ui.editor

import app.absentmotion.editor.keepCopy
import android.Manifest
import android.content.Context
import android.net.Uri
import app.absentmotion.ai.withVideoMatte
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.addAudio
import app.absentmotion.editor.videoLayer
import app.absentmotion.media.GifError
import app.absentmotion.media.GifProblem
import app.absentmotion.media.gifVideo
import app.absentmotion.media.isGif
import app.absentmotion.model.Layer
import app.absentmotion.platform.readAtMost
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addImage
import app.absentmotion.editor.addVideo
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.media.mediaThumbnail
import app.absentmotion.media.cachedMediaThumbnail
import app.absentmotion.ui.camera.CameraIcons
import app.absentmotion.ui.camera.CameraScreen
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.absentmotion.ui.theme.animatedBackground
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.presence
import app.absentmotion.platform.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private fun unsupportedPick(context: android.content.Context) {
    android.widget.Toast.makeText(context, context.getString(R.string.library_err_unsupported_type), android.widget.Toast.LENGTH_LONG).show()
}

private suspend fun looksLikeGif(context: Context, uri: Uri): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { isGif(it.readAtMost(6)) } }.getOrNull() == true
}

internal suspend fun EditorState.addPicked(context: Context, uri: Uri, video: Boolean? = null, svg: Boolean = false) {
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
    val name = uri.lastPathSegment.orEmpty()
    when {
        svg || type == "image/svg+xml" || name.endsWith(".svg", ignoreCase = true) -> importSvgFile(context, uri)
        video == null && type.startsWith("audio/") -> audioDeclaration(context, uri)?.let { keepCopy(context, it) }?.takeIf { it.duration > 0 }?.let { addAudio(it) } ?: unsupportedPick(context)
        video != true && !type.startsWith("video/") && looksLikeGif(context, uri) && addGif(context, uri) -> Unit
        else -> mediaDeclaration(context, uri)?.let { keepCopy(context, it) }?.let { if (it.type.startsWith("video/")) addVideo(it) else addImage(it) } ?: unsupportedPick(context)
    }
}

private suspend fun EditorState.addGif(context: Context, uri: Uri): Boolean {
    val preparing = android.widget.Toast.makeText(context, context.getString(R.string.import_gif_converting), android.widget.Toast.LENGTH_SHORT).also { it.show() }
    fun fail(message: Int): Boolean {
        preparing.cancel()
        android.widget.Toast.makeText(context, context.getString(message), android.widget.Toast.LENGTH_LONG).show()
        return true
    }
    val gif = try { gifVideo(context, uri) } catch (e: GifError) {
        return fail(if (e.problem == GifProblem.TooLarge) R.string.import_gif_too_large else R.string.import_gif_damaged)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: OutOfMemoryError) {
        return fail(R.string.import_gif_too_large)
    } catch (_: Exception) {
        return fail(R.string.import_gif_failed)
    }
    if (gif == null) { preparing.cancel(); return false }
    val media = keepCopy(context, gif.media)
    val matte = gif.matte?.let { keepCopy(context, it) }
    if (media.uri != gif.media.uri) gif.files.firstOrNull()?.delete()
    if (matte != null && matte.uri != gif.matte.uri) gif.files.getOrNull(1)?.delete()
    val placed = scene.videoLayer(media, frame) ?: return true
    val length = maxOf(media.duration / 1000, settings.defaultLayerDuration * 1000L).toInt()
    val layer = placed.copy(node = placed.node.with("loop", "true").with("endTime", "${scene.endTimeFor(frame, length)}"))
    val groupId = Layer.newId()
    edit { current ->
        val added = current.add(layer, listOf(media))
        matte?.let { added.withVideoMatte(layer, it, gif.width, gif.height, Layer.newId(), groupId)?.update(groupId) { g -> g.copy(node = g.node.with("label", media.title)) } } ?: added
    }
    selectedId = if (matte != null && scene.layer(groupId) != null) groupId else layer.id
    panel = EditorPanel.Tools
    addOpen = false
    return true
}

@Composable
internal fun MediaTab(state: EditorState, modifier: Modifier, library: AddLibrary) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    var album by remember { mutableStateOf<String?>(null) }
    var albumsOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    BackHandler(searching) { focus.clearFocus(); searching = false }
    val query = debounced(search)
    val media = library.media
    LaunchedEffect(media) { if (album != null && media.none { it.albumId == album }) album = null }
    val activity = androidx.activity.compose.LocalActivity.current
    val prefs = remember { context.getSharedPreferences("media", android.content.Context.MODE_PRIVATE) }
    val partial = android.os.Build.VERSION.SDK_INT >= 34
    val wanted = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOfNotNull(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { android.os.Build.VERSION.SDK_INT < 29 })
    }
    fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    fun fullAccess() = wanted.all(::granted)
    fun someAccess() = partial && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    var full by remember { mutableStateOf(fullAccess()) }
    var some by remember { mutableStateOf(someAccess()) }
    var answers by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { full = fullAccess(); some = someAccess() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        full = fullAccess()
        some = someAccess()
        if (!full && !some) prefs.edit().putBoolean("mediaRefused", true).apply()
        answers++
        library.mediaRevision++
        state.engine.refreshMedia()
    }
    val request = (wanted + listOfNotNull(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.takeIf { partial })).toTypedArray()
    val blocked = remember(answers, full, some) { !full && !some && prefs.getBoolean("mediaRefused", false) && wanted.none { activity?.shouldShowRequestPermissionRationale(it) == true } }
    fun ask() {
        if (blocked) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        else permission.launch(request)
    }
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("asked", false) && !full && !some) {
            prefs.edit().putBoolean("asked", true).apply()
            permission.launch(request)
        }
    }
    val viewAll = stringResource(R.string.view_all)
    val allLabel = stringResource(R.string.mediatab_all)
    val shown = remember(media, album, query) { media.filter { (album == null || it.albumId == album) && matchesSearch(query, it.name, it.album) } }
    Box(modifier.clipToBounds()) {
        LazyHorizontalGrid(AtLeastTwo(96.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            item(key = "albums") {
                Column(Modifier.width(97.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val press = remember { MutableInteractionSource() }
                    Box(Modifier.fillMaxWidth().weight(1f).pressScale(press, TilePress).background(Am.Well).semantics { contentDescription = viewAll }.clickable(press, LocalIndication.current) {
                        MediaPicks.openVisual()
                    }, Alignment.Center) { MediaGridIcon(Modifier.size(24.dp)) }
                    Box(Modifier.fillMaxWidth().weight(1f).background(Am.Well).clickable { albumsOpen = true }, Alignment.Center) {
                        Text(media.firstOrNull { it.albumId == album }?.album ?: allLabel, color = Am.OnDark, fontSize = 16.sp, maxLines = 1)
                        MediaAlbumArrow(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp))
                        val cell = remember { CellDrop() }
                        Presence(albumsOpen) {
                            Popup(cell, onDismissRequest = { albumsOpen = false }, properties = PopupProperties(focusable = true)) {
                                Column(Modifier.presence(scaleFrom = 0.85f, origin = cell.origin).width(220.dp).heightIn(max = 300.dp).background(Am.Field).verticalScroll(rememberScrollState())) {
                                    val albums = listOf(null to allLabel) + media.distinctBy { it.albumId }.map { it.albumId to it.album }
                                    albums.forEach { (id, name) ->
                                        Box(Modifier.fillMaxWidth().height(44.dp).clickable { album = id; albumsOpen = false }.padding(horizontal = 16.dp), Alignment.CenterStart) {
                                            Text(name, color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "camera") {
                Column(Modifier.width(97.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    MediaSquare(CameraIcons.Camera, stringResource(R.string.camera), false) { state.playing = false; state.cameraOpen = true }
                    MediaSquare(TextIcons.Search, stringResource(R.string.search_hint), searching || search.isNotEmpty()) { searching = true }
                }
            }
            item(key = "files") {
                Column(Modifier.width(97.dp).padding(6.dp)) {
                    MediaSquare(TextIcons.Import, stringResource(R.string.sounds_from_files), false) { MediaPicks.openFiles() }
                }
            }
            if (!full) item(key = "access", span = { GridItemSpan(maxLineSpan) }) {
                AccessNotice(Modifier.width(168.dp).fillMaxHeight().padding(6.dp), blocked, ::ask,
                    body = if (some) R.string.media_permission_partial else R.string.media_permission_body,
                    allow = if (some) R.string.media_permission_more else R.string.sounds_permission_allow)
            }
            if (shown.isEmpty() && query.isNotBlank()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                NoMatches(query, R.string.search_no_photos_or_videos, Modifier.width(200.dp).fillMaxHeight())
            }
            items(shown, key = { it.uri.toString() }) { file ->
                val bitmap by produceState<android.graphics.Bitmap?>(cachedMediaThumbnail(file.uri), file.uri, file.modified, library.mediaRevision) { value = mediaThumbnail(context, file.uri) }
                val press = remember { MutableInteractionSource() }
                Box(Modifier.fillMaxHeight().aspectRatio(1f).pressScale(press, TilePress).background(Color.Black).semantics { contentDescription = file.name }.clickable(press, LocalIndication.current, enabled = !state.addingMedia) {
                    scope.launch {
                        state.addingMedia = true
                        try {
                            state.addPicked(context, file.uri, video = file.video, svg = file.svg)
                        } finally { state.addingMedia = false }
                    }
                }) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (file.svg && bitmap == null) Text(file.name, Modifier.align(Alignment.Center).padding(6.dp), color = Am.OnDarkDim, fontSize = 11.sp, maxLines = 3,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (file.video) {
                        MediaPlayIcon(Modifier.align(Alignment.BottomStart).padding(5.dp).size(12.dp))
                        val seconds = file.durationMs / 1000
                        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                            Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x66000000)), color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
        Presence(searching) {
            Box(Modifier.align(Alignment.TopCenter).presence(slide = -1f).fillMaxWidth().background(Am.Panel).padding(6.dp)) {
                SearchField(search, { search = it }, Modifier.fillMaxWidth().height(36.dp), hint = stringResource(R.string.mediatab_search_hint), autoFocus = true,
                    onClose = { focus.clearFocus(); searching = false })
            }
        }
    }
}

internal class CellDrop : PopupPositionProvider {
    var origin by mutableStateOf(TransformOrigin(0f, 0f))
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val up = anchorBounds.top + popupContentSize.height > windowSize.height
        origin = TransformOrigin(if (rtl) 1f else 0f, if (up) 1f else 0f)
        return IntOffset(
            (if (rtl) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            if (up) (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(0) else anchorBounds.top,
        )
    }
}

@Composable
private fun ColumnScope.MediaSquare(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = animatedColor(if (active) Am.Accent else Am.OnDark)
    val press = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().weight(1f).pressScale(press, TilePress).animatedBackground(if (active) Am.Accent.copy(alpha = 0.16f) else Am.Well)
            .clickable(press, LocalIndication.current, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(label, Modifier.padding(horizontal = 4.dp), color = tint, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun EditorCamera(state: EditorState, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    CameraScreen(state.scene.width.toFloat() / state.scene.height, onClose = { state.cameraOpen = false }) { uri, video ->
        scope.launch {
            try {
                val media = mediaDeclaration(context, uri)?.let { state.keepCopy(context, it) }
                if (media == null) android.widget.Toast.makeText(context, context.getString(R.string.mediatab_capture_failed), android.widget.Toast.LENGTH_LONG).show()
                else if (video) state.addVideo(media) else state.addImage(media)
            } finally {
                state.cameraOpen = false
            }
        }
    }
}
