package app.absentmotion.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.effects.AddedEffectStore
import app.absentmotion.export.ExportError
import app.absentmotion.export.formatBytes
import app.absentmotion.export.writeProjectPackage
import app.absentmotion.i18n.AppText
import app.absentmotion.library.Arrival
import app.absentmotion.library.CodeRefreshMs
import app.absentmotion.library.ImportFailed
import app.absentmotion.library.ImportReport
import app.absentmotion.library.LinkChannel
import app.absentmotion.library.LinkCode
import app.absentmotion.library.LinkFailure
import app.absentmotion.library.LinkProblem
import app.absentmotion.library.LinkServer
import app.absentmotion.library.LinkVersion
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.Offer
import app.absentmotion.library.Peer
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.SentProjects
import app.absentmotion.library.SentRecord
import app.absentmotion.library.arrivalPlan
import app.absentmotion.library.arrive
import app.absentmotion.library.contentHash
import app.absentmotion.library.greet
import app.absentmotion.library.lanHosts
import app.absentmotion.library.linkConnect
import app.absentmotion.library.pictureCount
import app.absentmotion.library.receiveProject
import app.absentmotion.library.sendProject
import app.absentmotion.platform.Context
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.Uri
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.camera.CameraIcons
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class SendView { Code, Scan, Moving, Done, Problem }

internal class SendProblem(val title: String, val body: String? = null, val again: Boolean = false, val wifi: Boolean = false)

internal class Packed(val file: File, val title: String, val pictures: Int, val hash: String)

internal class Listener {
    @Volatile var server: LinkServer? = null
    @Volatile var stopped = false
    var job: Job? = null

    fun stop() {
        stopped = true
        server?.close()
        job?.cancel()
    }
}

internal object DeviceSend {
    var importer: (suspend (File) -> List<ImportReport>)? = null
    var opener: ((String) -> Unit)? = null
    var changed: () -> Unit = {}
    var serverAddress: java.net.InetAddress? = null
    var serverHosts: (() -> List<String>)? = null

    var view by mutableStateOf<SendView?>(null)
        private set
    var inline by mutableStateOf(false)
        private set
    var help by mutableStateOf(false)
    var project by mutableStateOf<ProjectInfo?>(null)
        private set
    var code by mutableStateOf<LinkCode?>(null)
        private set
    var peer by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var size by mutableLongStateOf(0L)
        private set
    var pictures by mutableIntStateOf(0)
        private set
    var moved by mutableLongStateOf(0L)
        private set
    var line by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<SendProblem?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pack: Deferred<Packed>? = null
    private var packed: Packed? = null
    private var packedFor: String? = null
    private var job: Job? = null
    private var channel: LinkChannel? = null
    var scanning = false
        private set

    fun showCode(context: Context, project: ProjectInfo?, inline: Boolean = false) {
        begin(context, project)
        this.inline = inline
        scanning = false
        view = SendView.Code
    }

    fun scan(context: Context, project: ProjectInfo?) {
        begin(context, project)
        scanning = true
        view = SendView.Scan
    }

    fun retry(context: Context) = if (scanning) scan(context, project) else showCode(context, project, inline)

    fun cancel() {
        stopLink()
        dropPack()
        clear()
    }

    fun close() = cancel()

    fun keepEditing() {
        val id = result
        close()
        if (id != null) opener?.invoke(id)
    }

    fun scanned(context: Context, text: String): Boolean {
        if (view != SendView.Scan) return false
        val code = LinkCode.parse(text) ?: return false
        when {
            code.version != LinkVersion -> fail(problemFor(LinkFailure(LinkProblem.Version), code.name))
            code.sends == sending -> fail(problemFor(LinkFailure(LinkProblem.SameMode), code.name))
            else -> {
                val app = context.applicationContext
                enterMoving(code.name)
                job = scope.launch {
                    val link = try {
                        linkConnect(code)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        fail(problemFor(e, code.name))
                        return@launch
                    }
                    run(app, link, client = true)
                }
            }
        }
        return true
    }

    fun listen(context: Context): Listener {
        val app = context.applicationContext
        val listener = Listener()
        listener.job = scope.launch {
            val me = withContext(Dispatchers.IO) { me(app) }
            val server = try {
                withContext(Dispatchers.IO) { LinkServer(me.name, me.computer, me.sends, sendWifiName(app), serverHosts ?: ::lanHosts, bind = serverAddress) }
            } catch (e: IOException) {
                fail(problemFor(LinkFailure(LinkProblem.Unreachable), ""))
                return@launch
            }
            listener.server = server
            if (listener.stopped) { server.close(); return@launch }
            val refresh = launch { while (true) { code = withContext(Dispatchers.IO) { server.issue() }; delay(CodeRefreshMs) } }
            val link = try {
                withContext(Dispatchers.IO) { server.accept() }
            } catch (e: IOException) {
                return@launch
            } finally {
                refresh.cancel()
                server.close()
            }
            if (listener.stopped || view != SendView.Code) { link.close(); return@launch }
            enterMoving("")
            job = scope.launch { run(app, link, client = false) }
        }
        return listener
    }

    private fun begin(context: Context, info: ProjectInfo?) {
        stopLink()
        clear()
        project = info
        sending = info != null
        title = info?.title.orEmpty()
        if (pack?.takeIf { info != null && packedFor == info.id && !it.isCancelled } != null) { packed?.let(::facts); return }
        dropPack()
        size = 0
        pictures = 0
        if (info == null) return
        val app = context.applicationContext
        packedFor = info.id
        val made = scope.async(Dispatchers.IO) { packProject(app, info.id) }
        pack = made
        made.invokeOnCompletion {
            scope.launch {
                if (pack !== made) return@launch
                runCatching { made.await() }
                    .onSuccess { packed = it; facts(it) }
                    .onFailure { e -> if (e !is CancellationException && view != SendView.Moving) fail(problemFor(e, "")) }
            }
        }
    }

    private fun facts(packed: Packed) {
        size = packed.file.length()
        pictures = packed.pictures
    }

    private fun enterMoving(name: String) {
        peer = name
        moved = 0
        line = null
        view = SendView.Moving
    }

    private fun clear() {
        view = null
        inline = false
        help = false
        code = null
        peer = ""
        moved = 0
        line = null
        result = null
        problem = null
        if (pack == null) { size = 0; pictures = 0 }
    }

    private fun stopLink() {
        val link = channel
        channel = null
        job?.cancel()
        job = null
        if (link != null) CoroutineScope(Dispatchers.IO).launch { link.cancel() }
    }

    private fun dropPack() {
        val old = pack ?: return
        pack = null
        packed = null
        packedFor = null
        old.cancel()
        old.invokeOnCompletion { e -> if (e == null) scope.launch(Dispatchers.IO) { runCatching { old.await().file.delete() } } }
    }

    private fun fail(reason: SendProblem) {
        stopLink()
        code = null
        problem = reason
        view = SendView.Problem
    }

    private fun progress(bytes: Long) {
        val step = (size / 200).coerceAtLeast(1)
        if (bytes - moved >= step || bytes >= size) moved = bytes
    }

    private suspend fun run(app: Context, link: LinkChannel, client: Boolean) {
        channel = link
        val release = holdFastWifi(app)
        try {
            val other = greet(link, withContext(Dispatchers.IO) { me(app) }, client)
            peer = other.name
            if (sending) sendTo(app, link, other) else receiveFrom(app, link, other)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (channel === link) fail(problemFor(e, peer))
        } finally {
            release()
            link.close()
            if (channel === link) channel = null
        }
    }

    private suspend fun sendTo(app: Context, link: LinkChannel, other: Peer) {
        val info = project ?: return
        val packed = (pack ?: throw LinkFailure(LinkProblem.Failed)).await()
        facts(packed)
        val records = SentProjects(sentFile(app))
        val back = withContext(Dispatchers.IO) { records[info.id] }?.takeIf { it.peer == other.id }?.peerProject
        val offer = Offer(packed.title, packed.file.length(), packed.pictures, info.id, back)
        val theirs = sendProject(link, offer, packed.file, { plan ->
            line = when (plan.plan) {
                Arrival.Replace -> AppText.get(R.string.send_replaces, other.name)
                Arrival.Both -> AppText.get(R.string.send_kept_both, plan.name.orEmpty())
                else -> null
            }
        }, ::progress)
        withContext(Dispatchers.IO) { records[info.id] = SentRecord(other.id, theirs, packed.hash) }
        moved = size
        dropPack()
        view = SendView.Done
    }

    private suspend fun receiveFrom(app: Context, link: LinkChannel, other: Peer) {
        val store = ProjectStore(app)
        val records = SentProjects(sentFile(app))
        val id = receiveProject(link, File(app.cacheDir, "send"), { offer ->
            title = offer.title
            size = offer.size
            pictures = offer.pictures
            arrivalPlan(store, records, offer, other) { AppText.get(if (other.computer) R.string.send_copy_computer else R.string.send_copy_phone, it) }.also { plan ->
                line = when (plan.plan) {
                    Arrival.Replace -> AppText.get(R.string.send_replaced)
                    Arrival.Both -> AppText.get(R.string.send_kept_both, plan.name.orEmpty())
                    else -> null
                }
            }
        }, ::progress) { file, offer, arrival ->
            val reports = (importer ?: throw LinkFailure(LinkProblem.Failed))(file)
            val report = reports.singleOrNull() ?: throw LinkFailure(LinkProblem.Failed)
            withContext(Dispatchers.IO) { arrive(store, records, report.id, arrival, other, offer) }
        }
        result = id
        moved = size
        changed()
        view = SendView.Done
    }

    private fun me(app: Context) = Peer(deviceId(app), sendDeviceName(app), PlatformFeatures.desktop, sending)

    private fun deviceId(app: Context): String {
        val prefs = app.getSharedPreferences("devicesend", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    private fun sentFile(app: Context) = File(app.filesDir, "sent-projects.json")

    private suspend fun packProject(app: Context, id: String): Packed {
        val store = ProjectStore(app)
        val text = store.text(id) ?: throw LinkFailure(LinkProblem.Failed)
        val scene = store.load(id) ?: throw LinkFailure(LinkProblem.Failed)
        val effects = AddedEffectStore(app.filesDir).bundled(scene)
        val file = File(File(app.cacheDir, "send").apply { mkdirs() }, "send-${UUID.randomUUID()}.absproj")
        try {
            file.outputStream().use { out ->
                writeProjectPackage(scene, scene.toXml(), out, MediaAccess.forApp(app), { uri -> runCatching { app.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull() }, null, effects) {}
            }
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return Packed(file, scene.title, pictureCount(scene), contentHash(text))
    }

    private fun problemFor(e: Throwable, name: String): SendProblem {
        val stopped = AppText.get(R.string.send_stopped)
        val failure = e as? LinkFailure
        return when (failure?.problem) {
            LinkProblem.Unreachable -> SendProblem(AppText.get(R.string.send_unreachable_title, name), AppText.get(R.string.send_unreachable), again = true, wifi = true)
            LinkProblem.Expired -> SendProblem(AppText.get(R.string.send_expired), again = true)
            LinkProblem.Version -> SendProblem(AppText.get(R.string.send_update))
            LinkProblem.SameMode -> SendProblem(AppText.get(R.string.send_same_mode), again = true)
            LinkProblem.Cancelled -> SendProblem(stopped, AppText.get(R.string.send_cancelled_on, name))
            LinkProblem.Damaged -> SendProblem(stopped, AppText.get(R.string.send_damaged), again = true)
            LinkProblem.Failed -> SendProblem(stopped, failure.detail)
            LinkProblem.Lost -> SendProblem(stopped, AppText.get(R.string.send_lost, name), again = true)
            null -> when (e) {
                is ImportFailed -> SendProblem(stopped, AppText.get(e.text))
                is app.absentmotion.library.ProjectLinkImportFailure -> SendProblem(stopped, e.detail)
                is ExportError -> SendProblem(stopped, AppText.get(e.text))
                is IOException -> SendProblem(stopped, AppText.get(R.string.send_lost, name), again = true)
                else -> SendProblem(stopped, e.message)
            }
        }
    }
}

@Composable
internal fun DeviceSendHost() {
    when (DeviceSend.view) {
        SendView.Code -> if (!DeviceSend.inline) CodeScreen()
        SendView.Scan -> ScanScreen()
        SendView.Moving -> MovingDialog()
        SendView.Done -> DoneDialog()
        SendView.Problem -> ProblemDialog()
        null -> Unit
    }
    if (DeviceSend.help && PlatformFeatures.desktop) HelpDialog()
}

@Composable
private fun factsText(): String = when {
    DeviceSend.size <= 0 -> stringResource(R.string.ai_preparing)
    DeviceSend.pictures <= 0 -> formatBytes(DeviceSend.size)
    else -> formatBytes(DeviceSend.size) + "  ·  " + pluralStringResource(R.plurals.send_pictures, DeviceSend.pictures, DeviceSend.pictures)
}

@Composable
private fun ProjectLine(title: String, facts: String, thumb: File?, dark: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (dark) Am.Field else Am.HomeBg).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture = rememberFileBitmap(thumb, thumb?.lastModified())
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(if (dark) Am.FieldHi else Am.HomeLine), Alignment.Center) {
            if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Icon(HomeIcons.ProjectsTabOff, null, Modifier.size(20.dp), tint = if (dark) Am.OnDarkDim else Am.HomeMid)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (dark) Am.OnDark else Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(facts, color = if (dark) Am.OnDarkDim else Am.HomeMid, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlanLine(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.HomeBg).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(HomeIcons.Replace, null, Modifier.size(20.dp), tint = Am.Navy)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Am.HomeInk, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

@Composable
private fun CodeServer() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val listener = DeviceSend.listen(context)
        onDispose { listener.stop() }
    }
}

@Composable
private fun CodeBox(modifier: Modifier) {
    val code = DeviceSend.code
    if (code != null) QrCode(code.text(), modifier)
    else Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.White), Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), color = Am.Accent, trackColor = Color.Transparent)
    }
}

@Composable
private fun CodeHints(dark: Boolean, center: Boolean) {
    val ink = if (dark) Am.OnDark else Am.Navy
    val dim = if (dark) Am.OnDarkDim else Am.HomeMid
    val align = if (center) TextAlign.Center else TextAlign.Start
    Text(stringResource(R.string.send_scan_with, stringResource(R.string.tools_send)), Modifier.fillMaxWidth(), color = ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, textAlign = align)
    DeviceSend.code?.wifi?.let { wifi ->
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), if (center) Arrangement.Center else Arrangement.Start, Alignment.CenterVertically) {
            Icon(HomeIcons.Wifi, null, Modifier.size(18.dp), tint = dim)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.send_wifi, wifi), color = dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (PlatformFeatures.desktop) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.send_help), Modifier.fillMaxWidth().clickable(role = Role.Button) { DeviceSend.help = true }.padding(vertical = 4.dp),
            color = dim, fontSize = 13.sp, textDecoration = TextDecoration.Underline, textAlign = align,
        )
    }
}

@Composable
private fun FullPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onBack, sheetDialogProperties()) {
        DialogStatusBarIcons(darkIcons = false)
        Column(Modifier.fillMaxSize().background(Am.PreviewBg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack), Alignment.Center) {
                    Icon(ExportIcons.Back, stringResource(R.string.home_back), Modifier.size(24.dp), tint = Am.OnDark)
                }
                Text(title, color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
private fun DarkPanel(pills: @Composable RowScope.() -> Unit) {
    val info = DeviceSend.project
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Am.TopBar).padding(16.dp)) {
        if (info != null) {
            ProjectLine(info.title, factsText(), info.thumbnail, dark = true)
            Spacer(Modifier.height(14.dp))
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) { pills() }
    }
}

@Composable
private fun CodeScreen() {
    val context = LocalContext.current
    if (PlatformFeatures.desktop) {
        SendPermitted(DeviceSend::cancel) {
            CodeServer()
            val info = DeviceSend.project
            AlertDialog(
                onDismissRequest = DeviceSend::cancel,
                title = {
                    Column {
                        Text(stringResource(if (info == null) R.string.send_receive_from_phone else R.string.send_to_phone), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (info != null) Text(info.title, color = Am.HomeMid, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CodeBox(Modifier.width(240.dp))
                        Column(Modifier.width(220.dp)) { CodeHints(dark = false, center = false) }
                    }
                },
                confirmButton = { DialogPill(stringResource(R.string.cancel), primary = false, onClick = DeviceSend::cancel) },
                containerColor = Am.Card,
            )
        }
        return
    }
    FullPage(stringResource(R.string.send_show_code), DeviceSend::cancel) {
        SendPermitted(DeviceSend::cancel) {
            CodeServer()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                CodeBox(Modifier.widthIn(max = 280.dp).fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                CodeHints(dark = true, center = true)
            }
        }
        DarkPanel {
            DialogPill(stringResource(R.string.send_scan_instead), Modifier.weight(1f), primary = false, editor = true) { DeviceSend.scan(context, DeviceSend.project) }
            DialogPill(stringResource(R.string.cancel), Modifier.weight(1f), primary = false, editor = true, onClick = DeviceSend::cancel)
        }
    }
}

@Composable
private fun ScanScreen() {
    val context = LocalContext.current
    FullPage(stringResource(R.string.send_scan_title), DeviceSend::cancel) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            SendPermitted(DeviceSend::cancel) {
                SendScanner(Modifier.fillMaxSize(), DeviceSend::cancel) { text -> DeviceSend.scanned(context, text) }
            }
        }
        DarkPanel {
            DialogPill(stringResource(R.string.send_show_code), Modifier.weight(1f), primary = false, editor = true) { DeviceSend.showCode(context, DeviceSend.project) }
            DialogPill(stringResource(R.string.cancel), Modifier.weight(1f), primary = false, editor = true, onClick = DeviceSend::cancel)
        }
    }
}

@Composable
internal fun Finder(modifier: Modifier) {
    val accent = Am.Accent
    Canvas(modifier) {
        val arm = size.minDimension * 0.18f
        val width = 4.dp.toPx()
        for ((x, y) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
            val dx = if (x == 0f) arm else -arm
            val dy = if (y == 0f) arm else -arm
            drawLine(accent, Offset(x, y), Offset(x + dx, y), width, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(accent, Offset(x, y), Offset(x, y + dy), width, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun MovingDialog() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val total = DeviceSend.size
    val fraction = if (total > 0) (DeviceSend.moved.toFloat() / total).coerceIn(0f, 1f) else 0f
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(if (DeviceSend.sending) R.string.send_sending_to else R.string.send_receiving_from, DeviceSend.peer),
                color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.widthIn(min = 280.dp)) {
                ProjectLine(DeviceSend.title.ifBlank { "…" }, factsText(), DeviceSend.project?.thumbnail)
                Spacer(Modifier.height(16.dp))
                if (total > 0) LinearProgressIndicator(
                    progress = { fraction }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Am.Accent, trackColor = Am.HomeLine,
                ) else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Am.Accent, trackColor = Am.HomeLine)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (total > 0) {
                        Text(stringResource(R.string.update_progress, formatBytes(DeviceSend.moved), formatBytes(total)), Modifier.weight(1f), color = Am.HomeMid, fontSize = 13.sp)
                        Text("${(fraction * 100).toInt()}%", color = Am.HomeMid, fontSize = 13.sp)
                    }
                }
                DeviceSend.line?.let {
                    Spacer(Modifier.height(14.dp))
                    PlanLine(it)
                }
            }
        },
        confirmButton = { DialogPill(stringResource(R.string.cancel), primary = false, onClick = DeviceSend::cancel) },
        containerColor = Am.Card,
    )
}

@Composable
private fun DoneDialog() {
    val context = LocalContext.current
    val received = DeviceSend.result
    val thumb = remember(received) { received?.let { ProjectStore(context).thumbnailFile(it) } ?: DeviceSend.project?.thumbnail }
    AlertDialog(
        onDismissRequest = DeviceSend::close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Am.Accent, CircleShape), Alignment.Center) { Icon(app.absentmotion.ui.editor.PanelIcons.Check, null, Modifier.size(18.dp), tint = Am.OnAccent) }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (DeviceSend.sending) R.string.send_sent_to else R.string.send_received_from, DeviceSend.peer),
                    color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.widthIn(min = 280.dp)) {
                ProjectLine(DeviceSend.title, factsText(), thumb)
                DeviceSend.line?.let {
                    Spacer(Modifier.height(14.dp))
                    PlanLine(it)
                }
            }
        },
        dismissButton = if (DeviceSend.sending) null else { { DialogPill(stringResource(R.string.send_stay), primary = false, onClick = DeviceSend::close) } },
        confirmButton = {
            if (DeviceSend.sending) DialogPill(stringResource(R.string.done), onClick = DeviceSend::close)
            else DialogPill(stringResource(R.string.template_keep_editing), onClick = DeviceSend::keepEditing)
        },
        containerColor = Am.Card,
    )
}

@Composable
private fun ProblemDialog() {
    val context = LocalContext.current
    val problem = DeviceSend.problem ?: return
    val settings = if (problem.wifi) wifiSettings(context) else null
    AlertDialog(
        onDismissRequest = DeviceSend::close,
        title = { Text(problem.title, color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = problem.body?.let { body -> { Text(body, color = Am.HomeInk, fontSize = 15.sp, lineHeight = 21.sp) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings != null) DialogPill(stringResource(R.string.send_wifi_settings), primary = false, onClick = settings)
                else if (problem.again) DialogPill(stringResource(R.string.cancel), primary = false, onClick = DeviceSend::close)
            }
        },
        confirmButton = {
            if (problem.again) DialogPill(stringResource(if (DeviceSend.scanning) R.string.send_scan_again else R.string.community_try_again)) { DeviceSend.retry(context) }
            else DialogPill(stringResource(R.string.ok), onClick = DeviceSend::close)
        },
        containerColor = Am.Card,
    )
}

@Composable
private fun HelpDialog() {
    val (body, action) = sendHelp()
    AlertDialog(
        onDismissRequest = { DeviceSend.help = false },
        title = { Text(stringResource(R.string.send_help), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(stringResource(body), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 21.sp) },
        dismissButton = if (action == null) null else { { DialogPill(stringResource(R.string.cancel), primary = false) { DeviceSend.help = false } } },
        confirmButton = {
            if (action != null) DialogPill(stringResource(action)) { DeviceSend.help = false; openSendSettings() }
            else DialogPill(stringResource(R.string.ok)) { DeviceSend.help = false }
        },
        containerColor = Am.Card,
    )
}

@Composable
internal fun SendScreen(store: ProjectStore, initial: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val desk = PlatformFeatures.desktop
    val projects by produceState(emptyList<ProjectInfo>(), DeviceSend.view) {
        value = withContext(Dispatchers.IO) { store.list().filter { !it.isElement }.sortedByDescending { it.modified } }
    }
    var chosen by remember { mutableStateOf(initial) }
    val picked = projects.firstOrNull { it.id == chosen } ?: projects.firstOrNull()
    if (desk) {
        LaunchedEffect(DeviceSend.view) { if (DeviceSend.view == null) DeviceSend.showCode(context, null, inline = true) }
        DisposableEffect(Unit) { onDispose { if (DeviceSend.inline) DeviceSend.cancel() } }
    }
    val list: @Composable () -> Unit = {
        if (desk) SectionTitle(stringResource(R.string.send_to_phone)) else Spacer(Modifier.height(16.dp))
        for (info in projects) SendRow(info, info.id == picked?.id) { chosen = info.id }
    }
    ToolSheet(stringResource(R.string.tools_send), onClose, bottom = {
        Row(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp), Arrangement.spacedBy(12.dp)) {
            if (desk) BigButton(stringResource(R.string.send_to_phone), Modifier.weight(1f), HomeIcons.Devices, enabled = picked != null) { DeviceSend.showCode(context, picked) }
            else {
                BigButton(stringResource(R.string.send_receive), Modifier.weight(1f), CameraIcons.Camera, primary = false) { DeviceSend.scan(context, null) }
                BigButton(stringResource(R.string.send_send), Modifier.weight(1f), HomeIcons.Devices, enabled = picked != null) { DeviceSend.scan(context, picked) }
            }
        }
    }, side = if (desk) { { list() } } else null) {
        if (!desk) list()
        else Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.send_receive_from_phone), Modifier.fillMaxWidth(), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (DeviceSend.view == SendView.Code && DeviceSend.inline) SendPermitted(onClose) {
                CodeServer()
                CodeBox(Modifier.width(260.dp).border(1.dp, Am.HomeLine, RoundedCornerShape(14.dp)))
                Spacer(Modifier.height(18.dp))
                CodeHints(dark = false, center = true)
            } else Box(Modifier.width(260.dp).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Am.HomeBg))
        }
    }
}

@Composable
private fun SendRow(info: ProjectInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Am.Navy else Am.Card).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture = rememberFileBitmap(info.thumbnail, info.modified)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Am.HomeLine), Alignment.Center) {
            if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.title, color = if (selected) Am.OnNavy else Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(info.sizeBytes), color = if (selected) Am.OnNavy.copy(alpha = 0.8f) else Am.HomeDim, fontSize = 13.sp)
        }
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) Am.OnNavy else Am.HomeLine), Alignment.Center) {
            Box(Modifier.size(if (selected) 10.dp else 16.dp).clip(CircleShape).background(if (selected) Am.Navy else Am.Card))
        }
    }
}
