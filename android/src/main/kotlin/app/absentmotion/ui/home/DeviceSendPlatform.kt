package app.absentmotion.ui.home

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.absentmotion.R
import app.absentmotion.platform.Context
import app.absentmotion.ui.camera.Permitted
import app.absentmotion.ui.theme.Am
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

private const val LocalNetwork = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LocalNetworkSdk = 37

internal fun sendDeviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

internal fun sendWifiName(context: Context): String? {
    if (Build.VERSION.SDK_INT < 29) return null
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val wifi = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.transportInfo as? WifiInfo }.getOrNull() ?: return null
    return wifi.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
}

internal fun wifiSettings(context: Context): (() -> Unit)? = {
    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun sendHelp(): Pair<Int, Int?> = R.string.send_help_linux to null

@Suppress("DEPRECATION")
internal fun holdFastWifi(context: Context): () -> Unit {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return {}
    val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
    val lock = runCatching { wifi.createWifiLock(mode, "AbsentMotion:send").apply { setReferenceCounted(false); acquire() } }.getOrNull() ?: return {}
    return { runCatching { if (lock.isHeld) lock.release() } }
}

internal fun openSendSettings() {}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
internal fun SendPermitted(onClose: () -> Unit, content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < LocalNetworkSdk) {
        content()
        return
    }
    val context = LocalContext.current
    fun allowed() = context.checkSelfPermission(LocalNetwork) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    var blocked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        blocked = !ok && context.activity()?.shouldShowRequestPermissionRationale(LocalNetwork) == false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = allowed() }
    if (granted) {
        content()
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        text = { Text(stringResource(if (blocked) R.string.send_nearby_blocked else R.string.send_nearby), color = Am.HomeInk) },
        dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false, onClick = onClose) },
        confirmButton = {
            DialogPill(stringResource(if (blocked) R.string.camera_open_settings else R.string.action_continue)) {
                if (blocked) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                else request.launch(LocalNetwork)
            }
        },
        containerColor = Am.Card,
    )
}

@Composable
internal fun SendScanner(modifier: Modifier, onClose: () -> Unit, onText: (String) -> Unit) {
    Box(modifier) {
        Permitted(onClose, withMic = false, explain = R.string.send_camera, blockedText = R.string.send_camera_blocked) { _, _ -> QrPreview(onText) }
    }
}

@Composable
private fun QrPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(owner) {
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = QRCodeReader()
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(worker) { image ->
            val text = try { readQr(image, reader) } finally { image.close() }
            if (text != null) main.execute { latest(text) }
        }
        controller.bindToLifecycle(owner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            worker.shutdown()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ PreviewView(it).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            this.controller = controller
        } }, Modifier.fillMaxSize())
        Finder(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).aspectRatio(1f))
    }
}

private val QrHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

private fun readQr(image: ImageProxy, reader: QRCodeReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val stride = plane.rowStride
    if (stride < image.width || data.size < stride * (image.height - 1) + image.width) return null
    val source = PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), QrHints).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
