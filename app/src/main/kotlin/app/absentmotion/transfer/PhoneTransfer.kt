package app.absentmotion.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.theme.Am
import app.absentmotion.library.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

const val SEND_TO_MAC_ENABLED = false

private fun stagingDir(context: Context) = File(context.cacheDir, "transfer-v2")

private fun clearStaging(context: Context) { stagingDir(context).deleteRecursively() }

private fun phonePack(context: Context, selected: Set<String>?): LibraryPack {
    val staging = stagingDir(context).apply { deleteRecursively(); mkdirs() }
    val root = context.filesDir
    val preferences = File(root, "preferences").apply { mkdirs() }
    for (name in listOf("settings", "fonts", "effects")) {
        val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        File(preferences, "$name.json").writeText(JSONObject(values.mapValues { (_, value) -> if (value is Set<*>) org.json.JSONArray(value.toList()) else value }).toString())
    }
    return LibraryPack.collect(root, "${Build.MANUFACTURER} ${Build.MODEL}", selected) { text ->
        val uri = Uri.parse(text)
        if (uri.scheme == "file") File(uri.path!!) else {
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(staging, key)
            runCatching { context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } } }.getOrNull()
                ?.let { file }
        }
    }
}

@Composable
fun PhoneTransferDialog(link: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects = remember { ProjectStore(context).list() }
    var everything by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(projects.map { it.id }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<LibraryPack?>(null) }
    var sender by remember { mutableStateOf<TransferSender?>(null) }
    var peer by remember { mutableStateOf("") }
    var peerHost by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { clearStaging(context) } }
    fun connect(raw: String) {
        scope.launch {
            busy = true; status = "Preparing library…"
            try {
                val address = TransferAddress.parse(raw)
                val remote = TransferSender(address)
                val name = withContext(Dispatchers.IO) { remote.peerName() }
                val library = withContext(Dispatchers.IO) { phonePack(context, if (everything) null else selected) }
                sender = remote; peer = name; peerHost = address.host; prepared = library; status = null
            } catch (e: Exception) {
                status = e.message ?: "Cannot reach the Mac. Both devices must use the same Wi-Fi or local network."
                withContext(Dispatchers.IO) { clearStaging(context) }
            }
            finally { busy = false }
        }
    }
    fun scan() = QrScanner.scan(context, onResult = { connect(it) }, onError = { status = it })
    var waiting by remember { mutableStateOf<String?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) waiting?.let(::connect) ?: scan() else status = "Allow local network access to send to your Mac. Both devices must use the same Wi-Fi or local network."
        waiting = null
    }
    fun start(raw: String?) {
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            waiting = raw
            permission.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else raw?.let(::connect) ?: scan()
    }
    var pasted by remember { mutableStateOf(link.orEmpty()) }
    LaunchedEffect(link) { link?.let { pasted = it } }
    val linkField = !QrScanner.AVAILABLE || link != null
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Send to Mac") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Both devices must be on the same Wi-Fi or local network. Open Receive from phone on your Mac.")
                if (prepared == null) {
                    Row(Modifier.clickable(enabled = !busy) { everything = true }) { RadioButton(everything, { everything = true }, enabled = !busy, colors = Radio); Text("Everything", Modifier.padding(top = 14.dp)) }
                    Row(Modifier.clickable(enabled = !busy) { everything = false }) { RadioButton(!everything, { everything = false }, enabled = !busy, colors = Radio); Text("Choose projects", Modifier.padding(top = 14.dp)) }
                    if (!everything) projects.forEach { p -> Row {
                        Checkbox(p.id in selected, { checked -> selected = if (checked) selected + p.id else selected - p.id }, enabled = !busy, colors = CheckboxDefaults.colors(checkedColor = Am.Navy))
                        Text(p.title, Modifier.padding(top = 14.dp))
                    } }
                    if (linkField) androidx.compose.material3.OutlinedTextField(
                        pasted, { pasted = it }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy, singleLine = true,
                        label = { Text("Link from your Mac") },
                        supportingText = { Text(if (link != null) "This link was opened from outside AbsentMotion. Connect only if it is the code your own Mac is showing." else "Scan the code on your Mac with your camera app, or paste the link shown there.") },
                    )
                } else {
                    val pack = prepared!!
                    val n = pack.manifest.entries.count { it.path.startsWith("projects/") && it.path.endsWith(".xml") }
                    val mb = pack.manifest.entries.sumOf { it.size } / 1_000_000.0
                    Text("Send $n projects (%.1f MB) to $peer ($peerHost)?".format(mb), Modifier.padding(top = 16.dp))
                    if (pack.missing.isNotEmpty()) Text("${pack.missing.size} media files are missing on this phone and will show as missing on the Mac too.", Modifier.padding(top = 8.dp), color = Am.Navy.copy(alpha = 0.7f))
                }
                status?.let { Text(it, Modifier.padding(top = 16.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = Am.Navy, trackColor = Am.HomeLine)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (everything || selected.isNotEmpty()) && (prepared != null || QrScanner.AVAILABLE || pasted.isNotBlank()), onClick = {
                val pack = prepared
                if (pack != null) {
                    busy = true
                    scope.launch {
                        try {
                            val start = System.nanoTime()
                            val result = withContext(Dispatchers.IO) { sender!!.send(pack) { files, bytes ->
                                val seconds = (System.nanoTime() - start) / 1e9
                                status = "$files files · %.1f MB · %.1f MB/s".format(bytes / 1e6, bytes / 1e6 / seconds.coerceAtLeast(.1))
                            } }
                            status = result.getString("summary"); prepared = null; sender = null
                            withContext(Dispatchers.IO) { clearStaging(context) }
                        } catch (e: Exception) { status = e.cause?.message ?: e.message }
                        finally { busy = false }
                    }
                } else {
                    if (QrScanner.AVAILABLE && pasted.isBlank()) start(null) else start(pasted.trim())
                }
            }) { Text(if (prepared != null) "Send" else if (QrScanner.AVAILABLE && pasted.isBlank()) "Scan QR code" else "Connect", color = Am.Navy, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Close", color = Am.Navy) } },
        containerColor = Am.Card, titleContentColor = Am.Navy, textContentColor = Am.Navy,
    )
}

private val Radio @Composable get() = RadioButtonDefaults.colors(selectedColor = Am.Navy)
