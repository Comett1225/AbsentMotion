package app.absentmotion.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskOs
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am
import java.io.File
import java.util.concurrent.TimeUnit

private fun output(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); "" } else process.inputStream.bufferedReader().readText()
}.getOrDefault("")

internal fun sendDeviceName(context: Context): String = runCatching {
    when {
        DeskOs.windows -> System.getenv("COMPUTERNAME")
        DeskOs.mac -> output("scutil", "--get", "ComputerName")
        else -> File("/etc/hostname").takeIf { it.isFile }?.readText() ?: output("hostname")
    }
}.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.take(64) ?: "Computer"

internal fun sendWifiName(context: Context): String? = when {
    DeskOs.linux -> output("nmcli", "-t", "-f", "active,ssid", "dev", "wifi").lineSequence().firstOrNull { it.startsWith("yes:") }?.substringAfter("yes:")
    DeskOs.mac -> output("ipconfig", "getsummary", "en0").lineSequence().map { it.trim() }.firstOrNull { it.startsWith("SSID : ") }?.substringAfter("SSID : ")
    else -> null
}?.trim()?.takeIf { it.isNotBlank() && !it.contains("redacted", ignoreCase = true) }

internal fun wifiSettings(context: Context): (() -> Unit)? = null

internal fun holdFastWifi(context: Context): () -> Unit = {}

internal fun sendHelp(): Pair<Int, Int?> = when {
    DeskOs.mac -> R.string.send_help_mac to R.string.send_open_mac
    DeskOs.windows -> R.string.send_help_windows to R.string.send_open_windows
    else -> R.string.send_help_linux to null
}

internal fun openSendSettings() {
    runCatching {
        when {
            DeskOs.mac -> ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork").start()
            DeskOs.windows -> ProcessBuilder("cmd", "/c", "start", "", "windowsdefender://network").start()
            else -> null
        }
    }
}

private val macAsks = DeskOs.mac && (System.getProperty("os.version").orEmpty().substringBefore('.').toIntOrNull() ?: 0) >= 15

@Composable
internal fun SendPermitted(onClose: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("devicesend", Context.MODE_PRIVATE) }
    var explained by remember { mutableStateOf(!(macAsks || DeskOs.windows) || prefs.getBoolean("explained", false)) }
    if (explained) {
        content()
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (DeskOs.mac) R.string.send_mac_title else R.string.send_windows_title), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Row {
                Icon(HomeIcons.Wifi, null, Modifier.size(36.dp), tint = Am.Navy)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(if (DeskOs.mac) R.string.send_mac_body else R.string.send_windows_body), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 22.sp)
            }
        },
        dismissButton = { DialogPill(stringResource(R.string.update_not_now), primary = false, onClick = onClose) },
        confirmButton = {
            DialogPill(stringResource(R.string.action_continue)) {
                prefs.edit().putBoolean("explained", true).apply()
                explained = true
            }
        },
        containerColor = Am.Card,
    )
}

@Composable
internal fun SendScanner(modifier: Modifier, onClose: () -> Unit, onText: (String) -> Unit) = Box(modifier)
