package app.absentmotion.ui.home

import android.app.ActivityManager
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.Tier
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am

internal const val LowMemoryBytes = 2_500_000_000L

internal fun lowMemory(totalBytes: Long): Boolean = totalBytes in 1 until LowMemoryBytes

@Composable
internal fun LowMemoryNotice() {
    if (!Tier.compatible) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("compatible", Context.MODE_PRIVATE) }
    val total = remember { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }
    var show by remember { mutableStateOf(lowMemory(total) && !prefs.getBoolean("lowMemoryShown", false)) }
    if (!show) return
    fun close() {
        prefs.edit().putBoolean("lowMemoryShown", true).apply()
        show = false
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.low_memory_title), color = Am.Navy) },
        text = { Text(stringResource(R.string.low_memory_body, String.format(LocalConfiguration.current.locales[0], "%.1f", total / 1e9)), color = Am.Navy) },
        confirmButton = { TextButton(::close) { Text(stringResource(R.string.ok), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
internal fun compatibleNote(): String? {
    if (!Tier.compatible) return null
    val left = Tier.leftOut.map { stringResource(it.label) }
    return if (left.isEmpty()) null else stringResource(R.string.about_compatible_note, left.joinToString(", "))
}
