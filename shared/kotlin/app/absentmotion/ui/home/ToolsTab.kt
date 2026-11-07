package app.absentmotion.ui.home

import android.content.ClipboardManager
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.editor.imageLayer
import app.absentmotion.editor.keepCopy
import app.absentmotion.editor.videoLayer
import app.absentmotion.export.AppFolder
import app.absentmotion.export.formatBytes
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectStore
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.tools.DownloadProblem
import app.absentmotion.tools.LinkInfo
import app.absentmotion.tools.LinkKind
import app.absentmotion.tools.LinkSite
import app.absentmotion.tools.Quality
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.SavedMedia
import app.absentmotion.tools.VideoDownloader
import app.absentmotion.tools.defaultQuality
import app.absentmotion.tools.findMediaLink
import app.absentmotion.tools.qualities
import app.absentmotion.tools.usualKbps
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ToolsTab(modifier: Modifier, store: ProjectStore, settings: AppSettings, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by VideoDownloader.revision.collectAsState()
    var engine by remember { mutableStateOf(if (!VideoDownloader.available) Engine.Unavailable else if (VideoDownloader.installed(context)) Engine.Ready else Engine.Missing) }
    var open by rememberSaveable { mutableIntStateOf(0) }
    var compressFrom by remember { mutableStateOf<Uri?>(null) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        if (engine !is Engine.Getting) engine = if (!VideoDownloader.available) Engine.Unavailable else if (VideoDownloader.installed(context)) Engine.Ready else Engine.Missing
    }
    fun getDownloader() {
        if (engine is Engine.Getting) return
        engine = Engine.Getting(0)
        scope.launch {
            engine = try {
                VideoDownloader.install(context) { fraction ->
                    val percent = (fraction * 100).toInt().coerceIn(0, 100)
                    if (engine != Engine.Getting(percent)) engine = Engine.Getting(percent)
                }
                Engine.Ready
            } catch (e: CancellationException) {
                engine = Engine.Missing
                throw e
            } catch (e: Exception) {
                val error = problem(e).takeIf { it != R.string.downloader_error_unknown } ?: R.string.downloader_engine_failed
                Toast.makeText(context, context.getString(error), Toast.LENGTH_LONG).show()
                Engine.Failed(error)
            }
        }
    }
    val compressCard: @Composable (Modifier) -> Unit = { m ->
        ToolCard(HomeIcons.Compress, stringResource(R.string.tools_compress), stringResource(R.string.tools_compress_about), modifier = m) { compressFrom = null; open = 1 }
    }
    val trimCard: @Composable (Modifier) -> Unit = { m ->
        ToolCard(HomeIcons.Trim, stringResource(R.string.tools_trim), stringResource(R.string.tools_trim_about), modifier = m) { open = 3 }
    }
    val extractCard: @Composable (Modifier) -> Unit = { m ->
        ToolCard(HomeIcons.Audio, stringResource(R.string.tools_extract), stringResource(R.string.tools_extract_about), modifier = m) { open = 4 }
    }
    val gifCard: @Composable (Modifier) -> Unit = { m ->
        ToolCard(HomeIcons.Gif, stringResource(R.string.tools_gif), stringResource(R.string.tools_gif_about), modifier = m) { open = 5 }
    }
    val downloaderCard: @Composable (Modifier) -> Unit = { m ->
        ToolCard(HomeIcons.Download, stringResource(R.string.tools_downloader), stringResource(R.string.tools_downloader_about), engine = engine, modifier = m) {
            when {
                engine is Engine.Getting -> Unit
                !VideoDownloader.available || VideoDownloader.installed(context) -> open = 2
                else -> asking = true
            }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (PlatformFeatures.desktop) 28.dp else 16.dp)) {
        Spacer(Modifier.height(24.dp))
        if (PlatformFeatures.desktop) ToolGrid(listOf(compressCard, trimCard, extractCard, gifCard))
        else if (LocalWide.current || app.absentmotion.ui.editor.LocalTablet.current) ToolGrid(listOf(compressCard, trimCard, extractCard, gifCard, downloaderCard))
        else {
            compressCard(Modifier)
            Spacer(Modifier.height(12.dp))
            trimCard(Modifier)
            Spacer(Modifier.height(12.dp))
            extractCard(Modifier)
            Spacer(Modifier.height(12.dp))
            gifCard(Modifier)
            Spacer(Modifier.height(12.dp))
            downloaderCard(Modifier)
        }
        Spacer(Modifier.height(28.dp))
        ToolsFooter()
        if (asking) AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.tools_downloader), color = Am.Navy) },
            text = { Text(stringResource(R.string.tools_downloader_intro, (VideoDownloader.downloadBytes / (1024f * 1024f)).roundToInt()), color = Am.Navy) },
            confirmButton = { TextButton({ asking = false; getDownloader() }) { Text(stringResource(R.string.downloader_download), color = Am.Accent) } },
            dismissButton = { TextButton({ asking = false }) { Text(stringResource(R.string.home_back), color = Am.Navy) } },
            containerColor = Am.Card,
        )
        Spacer(Modifier.height(28.dp))
    }
    when (open) {
        1 -> CompressScreen(compressFrom) { open = 0; compressFrom = null }
        2 -> DownloaderScreen(store, settings, onOpen = { id -> open = 0; onOpen(id) }, onCompress = { uri -> compressFrom = uri; open = 1 }) { open = 0 }
        3 -> TrimScreen { open = 0 }
        4 -> ExtractAudioScreen { open = 0 }
        5 -> GifScreen { open = 0 }
    }
}

@Composable
private fun ToolGrid(cards: List<@Composable (Modifier) -> Unit>) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = ((maxWidth + 16.dp) / 336.dp).toInt().coerceIn(1, 3)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, about: String, engine: Engine? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val missing = engine == Engine.Missing || engine is Engine.Getting || engine is Engine.Failed
    Row(
        modifier.fillMaxWidth().heightIn(min = 104.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).deskPress(12.dp).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).alpha(if (missing) 0.45f else 1f).background(Am.Navy, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = Am.OnNavy)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.alpha(if (missing) 0.45f else 1f), color = Am.Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth()) {
                Text(about, if (missing) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier, color = Am.HomeDim, fontSize = 15.sp, lineHeight = 20.sp)
                if (missing) Text(
                    if (engine is Engine.Getting) stringResource(R.string.tools_download_progress, engine.percent)
                    else stringResource(R.string.tools_download_addon, (VideoDownloader.downloadBytes / (1024f * 1024f)).roundToInt()),
                    Modifier.matchParentSize(), color = Am.Accent, fontSize = 15.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp).alpha(if (missing) 0.45f else 1f), tint = Am.HomeMid)
    }
}

@Composable
private fun ToolsFooter() {
    val links = LocalUriHandler.current
    val found = rememberReportLinks()
    val github = stringResource(R.string.tools_github)
    val form = stringResource(R.string.tools_form)
    val discord = "Discord"
    val message = stringResource(R.string.tools_suggestions, github, form, discord)
    val style = TextLinkStyles(style = SpanStyle(color = Am.Navy, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        append(message)
        listOf(github to "$HOME_PAGE/issues/new/choose", form to found.feature, discord to found.discord).forEach { (label, url) ->
            val start = message.indexOf(label)
            if (start >= 0) addLink(LinkAnnotation.Clickable(url, style) { runCatching { links.openUri(url) } }, start, start + label.length)
        }
    }
    Text(stringResource(R.string.tools_more), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(6.dp))
    Text(text, color = Am.HomeDim, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
internal fun ToolSheet(
    title: String, onBack: () -> Unit, bounded: Boolean = false, bottom: @Composable ColumnScope.() -> Unit = {},
    side: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onBack, sheetDialogProperties()) {
        val body: @Composable (Boolean) -> Unit = { twoColumns ->
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Column(Modifier.fillMaxSize().background(Am.SheetBg).navigationBarsPadding()) {
                TitleBar(title, ExportIcons.Back, stringResource(R.string.home_back), onBack)
                if (twoColumns && side != null) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp), content = content)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(end = 16.dp), content = side)
                        bottom()
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        content()
                        side?.invoke(this)
                    }
                    bottom()
                }
            }
        }
        if (bounded || !PlatformFeatures.desktop && (LocalWide.current || app.absentmotion.ui.editor.LocalTablet.current)) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Am.SheetBg), contentAlignment = Alignment.Center) {
            val twoColumns = side != null && maxWidth >= WideToolSheet && maxWidth > maxHeight
            Box(Modifier.widthIn(max = if (twoColumns) 1040.dp else 560.dp).fillMaxSize()) { body(twoColumns) }
        } else PhoneScreen { body(false) }
    }
}

private val WideToolSheet = 840.dp

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 10.dp), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun ChoiceRow(title: String, about: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = if (about == null) 52.dp else 64.dp).alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp)).background(animatedColor(if (selected) Am.Navy else Am.Card))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) Am.OnNavy else Am.HomeLine), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (selected) 10.dp else 16.dp).clip(CircleShape).background(if (selected) Am.Navy else Am.Card))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) Am.OnNavy else Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (about != null) Text(about, color = if (selected) Am.OnNavy.copy(alpha = 0.8f) else Am.HomeDim, fontSize = 14.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun BigButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(14.dp)).background(if (primary) Am.Accent else Am.HomeLine)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (primary) Am.OnAccent else Am.Navy
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = tint, fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun LinkKind.label(): String = stringResource(when (this) {
    LinkKind.Video -> R.string.downloader_kind_video
    LinkKind.Short -> R.string.downloader_kind_short
    LinkKind.Reel -> R.string.downloader_kind_reel
    LinkKind.Live -> R.string.downloader_kind_live
    LinkKind.Story -> R.string.downloader_kind_story
    LinkKind.Clip -> R.string.downloader_kind_clip
    LinkKind.Playlist -> R.string.downloader_kind_playlist
    LinkKind.Post -> R.string.downloader_kind_post
    LinkKind.Photos -> R.string.downloader_kind_photos
})

private val SiteSupport: List<Pair<LinkSite, Int?>> = listOf(
    LinkSite.YouTube to null,
    LinkSite.Pinterest to null,
    LinkSite.Twitch to null,
    LinkSite.Dailymotion to null,
    LinkSite.Instagram to R.string.downloader_partly_instagram,
    LinkSite.TikTok to R.string.downloader_partly_tiktok,
    LinkSite.X to R.string.downloader_partly_x,
    LinkSite.Facebook to R.string.downloader_partly_facebook,
    LinkSite.Bluesky to R.string.downloader_partly_bluesky,
    LinkSite.Snapchat to R.string.downloader_partly_snapchat,
)

private sealed interface Engine {
    data object Unavailable : Engine
    data object Missing : Engine
    data class Getting(val percent: Int) : Engine
    data class Failed(@StringRes val text: Int) : Engine
    data object Ready : Engine
}

private sealed interface Check {
    data object Idle : Check
    data object Checking : Check
    data class Checked(val url: String, val info: LinkInfo) : Check
    data class Failed(@StringRes val text: Int) : Check
}

private sealed interface Fetch {
    data object Ready : Fetch
    data class Running(val percent: Int) : Fetch
    data class Done(val saved: List<SavedMedia>) : Fetch
    data class Failed(@StringRes val text: Int) : Fetch
}

private fun problem(e: Exception): Int = (e as? DownloadProblem)?.text ?: R.string.downloader_error_unknown

@Composable
private fun DownloaderScreen(store: ProjectStore, settings: AppSettings, onOpen: (String) -> Unit, onCompress: (Uri) -> Unit, onClose: () -> Unit) {
    val storage = app.absentmotion.platform.rememberStorageAccess()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var pasteMissed by remember { mutableStateOf(false) }
    var save by rememberSaveable { mutableIntStateOf(0) }
    var choice by remember { mutableIntStateOf(-1) }
    var engine by remember { mutableStateOf(if (!VideoDownloader.available) Engine.Unavailable else if (VideoDownloader.installed(context)) Engine.Ready else Engine.Missing) }
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    var fetch by remember { mutableStateOf<Fetch>(Fetch.Ready) }
    var work by remember { mutableStateOf<Job?>(null) }
    var why by remember { mutableStateOf<Pair<LinkSite, Int>?>(null) }
    val link = remember(text) { findMediaLink(text) }
    val ready = engine == Engine.Ready
    val checked = check as? Check.Checked
    val info = checked?.info
    val single = info != null && info.items.size == 1
    val pictures = link?.kind?.pictures == true && info == null || info?.pictures == true
    val mode = SaveMode.entries[save]
    val options = remember(info, mode) { if (info != null && single) qualities(info.items.first(), mode, VideoDownloader.av1Hardware) else emptyList() }
    val running = fetch is Fetch.Running

    LaunchedEffect(options) { choice = defaultQuality(options, mode) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        VideoDownloader.warmUp(context)
        VideoDownloader.updateIfDue(context)
    }
    LaunchedEffect(link?.url, ready) {
        check = Check.Idle
        fetch = Fetch.Ready
        val url = link?.url ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (link.kind == LinkKind.Playlist) {
            check = Check.Failed(R.string.downloader_error_playlist)
            return@LaunchedEffect
        }
        delay(350)
        check = Check.Checking
        check = try {
            Check.Checked(url, VideoDownloader.check(context, url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Check.Failed(problem(e))
        }
    }

    fun getEngine() {
        engine = Engine.Getting(0)
        scope.launch {
            engine = try {
                VideoDownloader.install(context) { fraction ->
                    val percent = (fraction * 100).toInt().coerceIn(0, 100)
                    if (engine != Engine.Getting(percent)) engine = Engine.Getting(percent)
                }
                Engine.Ready
            } catch (e: CancellationException) {
                engine = Engine.Missing
                throw e
            } catch (e: Exception) {
                Engine.Failed(problem(e).takeIf { it != R.string.downloader_error_unknown } ?: R.string.downloader_engine_failed)
            }
        }
    }

    fun startDownload() {
        val target = checked ?: return
        if (!storage.ready { startDownload() }) return
        fetch = Fetch.Running(0)
        work = scope.launch {
            fetch = try {
                Fetch.Done(VideoDownloader.download(context, target.url, target.info, mode, choice) { fraction ->
                    val percent = (fraction * 100).toInt().coerceIn(0, 100)
                    if (fetch != Fetch.Running(percent)) fetch = Fetch.Running(percent)
                })
            } catch (e: CancellationException) {
                fetch = Fetch.Ready
                throw e
            } catch (e: Exception) {
                Fetch.Failed(problem(e))
            }
        }
    }

    fun change(block: () -> Unit) {
        if (running) return
        block()
        if (fetch !is Fetch.Ready) fetch = Fetch.Ready
    }

    val back = { if (running) work?.cancel() ?: Unit else onClose() }
    ToolSheet(stringResource(R.string.tools_downloader), back, bounded = true, bottom = {
        val view = LocalView.current
        DisposableEffect(running) {
            view.keepScreenOn = running
            onDispose { view.keepScreenOn = false }
        }
        DownloadBar(
            fetch, enabled = ready && info != null && (pictures || !single || options.isNotEmpty()),
            onStart = ::startDownload,
            onCancel = { work?.cancel() },
            onProject = { media ->
                scope.launch {
                    val id = try { projectFrom(context, store, settings, media) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (id == null) Toast.makeText(context, context.getString(R.string.home_create_project_failed), Toast.LENGTH_LONG).show() else onOpen(id)
                }
            },
            onCompress = { media -> onCompress(media.uri) },
        )
    }, content = {
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(stringResource(R.string.downloader_hint), color = Am.HomeDim, fontSize = 16.sp, maxLines = 1)
                BasicTextField(
                    text, { typed -> change { text = typed; pasteMissed = false } }, Modifier.fillMaxWidth(), singleLine = true, enabled = !running,
                    textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp), cursorBrush = SolidColor(Am.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
            }
            Box(Modifier.size(44.dp).alpha(if (text.isEmpty()) 0f else 1f).clickable(enabled = text.isNotEmpty() && !running) { text = ""; pasteMissed = false }, contentAlignment = Alignment.Center) {
                Icon(HomeIcons.ClearCircle, stringResource(R.string.downloader_clear), Modifier.size(22.dp), tint = Am.HomeMid)
            }
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(Am.Navy).clickable(enabled = !running) {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val copied = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    val found = findMediaLink(copied)
                    if (found != null) { text = found.url; pasteMissed = false } else pasteMissed = true
                }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.downloader_paste), color = Am.OnNavy, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Text(
            if (pasteMissed) stringResource(R.string.downloader_nothing_to_paste) else "",
            Modifier.fillMaxWidth().height(42.dp).padding(top = 6.dp),
            color = Am.Accent, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
        )
        LinkCard(link, check, if (engine == Engine.Unavailable) R.string.downloader_engine_unavailable else if (ready) null else R.string.downloader_get_engine_first, typed = text.isNotBlank())
        if (engine != Engine.Ready) {
            Spacer(Modifier.height(12.dp))
            EngineCard(engine, ::getEngine)
        }
    }, side = {
        SectionTitle(stringResource(R.string.downloader_what))
        Box {
            Column(Modifier.alpha(if (pictures) 0f else 1f)) {
                listOf(R.string.downloader_video_audio, R.string.downloader_video_only, R.string.downloader_audio_only).forEachIndexed { i, label ->
                    ChoiceRow(stringResource(label), null, save == i, enabled = !pictures && !running && (info == null || single)) { change { save = i } }
                }
                SectionTitle(stringResource(R.string.downloader_quality))
                QualityRow(check, single, options, choice, enabled = !running) { picked -> change { choice = picked } }
            }
            if (pictures) Text(
                stringResource(R.string.downloader_pictures_note),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(16.dp),
                color = Am.HomeInk, fontSize = 15.sp, lineHeight = 20.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        SupportBlock { site, reason -> why = site to reason }
        Spacer(Modifier.height(24.dp))
    })
    why?.let { (site, reason) ->
        AlertDialog(
            onDismissRequest = { why = null },
            title = { Text(site.title, color = Am.Navy) },
            text = { Text(stringResource(reason), color = Am.Navy) },
            confirmButton = { TextButton({ why = null }) { Text(stringResource(R.string.ok), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

private fun duration(seconds: Double): String? {
    if (seconds <= 0) return null
    val total = seconds.roundToInt()
    return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

@Composable
private fun LinkCard(link: app.absentmotion.tools.MediaLink?, check: Check, @StringRes waiting: Int?, typed: Boolean) {
    val info = (check as? Check.Checked)?.info
    val thumb by produceState<ImageBitmap?>(null, info?.thumbnails) { value = info?.thumbnails?.let { VideoDownloader.thumbnail(it) } }
    Row(
        Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(if (link != null) Am.HomeLine else Am.Card), contentAlignment = Alignment.Center) {
            val image = thumb
            when {
                check == Check.Checking -> CircularProgressIndicator(Modifier.size(26.dp), color = Am.Accent, strokeWidth = 2.5.dp)
                image != null -> Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Icon(
                    if (link?.kind?.pictures == true || info?.pictures == true) ExportIcons.Frame else if (link != null) ExportIcons.Video else HomeIcons.Link, null,
                    Modifier.size(28.dp), tint = if (link != null) Am.Navy else Am.HomeDim,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (link == null) {
                Text(
                    stringResource(if (typed) R.string.downloader_unknown else R.string.downloader_waiting),
                    color = Am.HomeDim, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2,
                )
                return@Column
            }
            val title = info?.title?.takeIf { it.isNotBlank() } ?: link.site.title
            Text(title, color = Am.Navy, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = if (info != null) 2 else 1, overflow = TextOverflow.Ellipsis)
            val facts = when {
                info != null -> listOfNotNull(
                    link.site.title,
                    if (info.items.size > 1) stringResource(R.string.downloader_items, info.items.size) else link.kind.label(),
                    duration(info.first?.durationSeconds ?: 0.0).takeIf { info.items.size == 1 },
                ).joinToString(" · ")
                else -> link.kind.label()
            }
            val (status, warn) = when {
                check is Check.Failed -> stringResource(check.text) to true
                check == Check.Checking -> stringResource(R.string.downloader_checking) to false
                waiting != null -> stringResource(waiting) to false
                else -> facts to false
            }
            Text(status, color = if (warn) Am.Accent else Am.HomeMid, fontSize = 14.sp, lineHeight = 18.sp, maxLines = if (info != null) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EngineCard(engine: Engine, onGet: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(16.dp)) {
        if (engine == Engine.Unavailable) {
            Text(stringResource(R.string.downloader_engine_unavailable), color = Am.Navy, fontSize = 15.sp, lineHeight = 20.sp)
            return@Column
        }
        Text(stringResource(R.string.downloader_engine_title), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.downloader_engine_about, formatBytes(VideoDownloader.downloadBytes)), color = Am.HomeDim, fontSize = 14.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
            if (engine is Engine.Getting) Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.downloader_engine_getting, engine.percent), color = Am.Navy, fontSize = 15.sp, maxLines = 1)
                LinearProgressIndicator(
                    progress = { engine.percent / 100f }, Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(4.dp)),
                    color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f),
                )
            } else BigButton(stringResource(R.string.downloader_engine_get), Modifier.fillMaxWidth(), HomeIcons.Download, onClick = onGet)
        }
        if (engine is Engine.Failed) Text(stringResource(engine.text), Modifier.padding(top = 8.dp), color = Am.Accent, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Quality.label(): String {
    val main = when {
        side > 0 -> "${side}p" + if (fps > 0) "$fps" else ""
        kbps > 0 -> stringResource(R.string.downloader_kbps, usualKbps(kbps))
        else -> stringResource(R.string.downloader_quality_original)
    }
    val bytes = plan.bytes
    return if (bytes > 0) main + " · " + stringResource(R.string.downloader_about_size, formatBytes(bytes)) else main
}

@Composable
private fun QualityRow(check: Check, single: Boolean, options: List<Quality>, choice: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val usable = single && options.isNotEmpty()
    Box {
        Row(
            Modifier.fillMaxWidth().height(52.dp).alpha(if (usable && enabled) 1f else 0.6f).clip(RoundedCornerShape(12.dp)).background(Am.Card)
                .clickable(enabled = usable && enabled) { open = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (check == Check.Checking) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Am.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
            } else {
                val label = when {
                    usable -> options.getOrNull(choice)?.label() ?: options.first().label()
                    check is Check.Checked && !single -> stringResource(R.string.downloader_quality_each)
                    check is Check.Checked || check is Check.Failed -> stringResource(R.string.downloader_quality_none)
                    else -> stringResource(R.string.downloader_quality_later)
                }
                Text(label, Modifier.weight(1f), color = if (usable) Am.Navy else Am.HomeDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp).alpha(if (usable) 1f else 0f), tint = Am.Navy)
        }
        DropdownMenu(open, { open = false }, containerColor = Am.Card, shape = if (app.absentmotion.platform.PlatformFeatures.desktop) androidx.compose.foundation.shape.RoundedCornerShape(10.dp) else androidx.compose.material3.MenuDefaults.shape) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option.label(), color = Am.Navy, fontSize = 16.sp, fontWeight = if (i == choice) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

@Composable
private fun SupportBlock(onWhy: (LinkSite, Int) -> Unit) {
    val full = SiteSupport.filter { it.second == null }.map { it.first }
    val partial = SiteSupport.mapNotNull { (site, reason) -> reason?.let { site to it } }
    if (full.isNotEmpty()) {
        Text(
            stringResource(R.string.downloader_supported) + " " + full.joinToString(", ") { it.title },
            color = Am.HomeDim, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
    if (partial.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.downloader_partly), Modifier.padding(vertical = 4.dp), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 18.sp)
            partial.forEachIndexed { i, (site, reason) ->
                Text(
                    site.title + if (i < partial.lastIndex) "," else "",
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable { onWhy(site, reason) }.padding(vertical = 4.dp, horizontal = 2.dp),
                    color = Am.Navy, fontSize = 13.sp, lineHeight = 18.sp, textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

private fun folderOf(media: SavedMedia): String = when {
    media.mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
    media.mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
    else -> Environment.DIRECTORY_MOVIES
} + "/" + AppFolder

@Composable
private fun DownloadBar(fetch: Fetch, enabled: Boolean, onStart: () -> Unit, onCancel: () -> Unit, onProject: (SavedMedia) -> Unit, onCompress: (SavedMedia) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
            when (fetch) {
                is Fetch.Running -> Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.downloader_downloading, fetch.percent), color = Am.Navy, fontSize = 15.sp, maxLines = 1)
                    LinearProgressIndicator(
                        progress = { fetch.percent / 100f }, Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(4.dp)),
                        color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f),
                    )
                }
                is Fetch.Done -> {
                    val folders = fetch.saved.map(::folderOf).distinct().joinToString(" · ")
                    val message = if (fetch.saved.size == 1) stringResource(R.string.compress_saved_to, folders) else stringResource(R.string.downloader_saved_many, fetch.saved.size, folders)
                    Text(message, color = Am.Navy, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                is Fetch.Failed -> Text(stringResource(fetch.text), color = Am.Accent, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Fetch.Ready -> Unit
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val first = (fetch as? Fetch.Done)?.saved?.firstOrNull { !it.mime.startsWith("audio/") }
            when {
                fetch is Fetch.Running -> BigButton(stringResource(R.string.cancel), Modifier.weight(1f), primary = false, onClick = onCancel)
                first != null -> {
                    BigButton(stringResource(R.string.downloader_new_project), Modifier.weight(1f), HomeIcons.Plus) { onProject(first) }
                    BigButton(stringResource(R.string.tools_compress), Modifier.weight(1f), HomeIcons.Compress, primary = false) { onCompress(first) }
                }
                else -> BigButton(stringResource(R.string.downloader_download), Modifier.weight(1f), HomeIcons.Download, enabled = enabled, onClick = onStart)
            }
        }
        Text(stringResource(R.string.downloader_rights), Modifier.fillMaxWidth().padding(top = 10.dp), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

private suspend fun projectFrom(context: android.content.Context, store: ProjectStore, settings: AppSettings, saved: SavedMedia): String {
    val media = mediaDeclaration(context, saved.uri) ?: error("unreadable")
    val id = withContext(Dispatchers.IO) { store.newId() }
    val kept = keepCopy(context, settings, store, id, media, null)
    val turned = kept.orientation % 180 != 0
    val width = if (turned) kept.height else kept.width
    val height = if (turned) kept.width else kept.height
    check(width > 0 && height > 0)
    val scale = minOf(1.0, 3840.0 / maxOf(width, height))
    val w = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(32)
    val h = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(32)
    val fps = (kept.fps ?: 30f).roundToInt().coerceIn(12, 60)
    val scene = Scene.create(saved.name.substringBeforeLast('.').take(60), w, h, fps, 0xFF000000.toInt())
    val layer = (if (saved.video) scene.videoLayer(kept, 0) else scene.imageLayer(kept, 0, settings.defaultLayerDuration)) ?: error("no layer")
    withContext(Dispatchers.IO) { store.save(id, scene.add(layer, listOf(kept))) }
    return id
}
