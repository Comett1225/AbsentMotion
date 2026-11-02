package app.absentmotion.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.theme.Am

internal class StorageAccess(private val sdk: Int, private val granted: () -> Boolean, private val ask: () -> Unit, private val denied: () -> Unit = {}) {
    private var again: (() -> Unit)? = null

    fun ready(retry: () -> Unit): Boolean {
        if (sdk >= 29 || granted()) return true
        again = retry
        ask()
        return false
    }

    fun answered(allowed: Boolean) {
        val retry = again ?: return
        again = null
        if (allowed) retry() else denied()
    }
}

@Composable
internal fun rememberStorageAccess(): StorageAccess {
    val context = LocalContext.current
    val launcher = remember { arrayOfNulls<ActivityResultLauncher<String>>(1) }
    var explain by remember { mutableStateOf(false) }
    val access = remember {
        StorageAccess(
            Build.VERSION.SDK_INT,
            { context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED },
            { launcher[0]?.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
            { explain = true },
        )
    }
    launcher[0] = rememberLauncherForActivityResult(RequestPermission()) { access.answered(it) }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        text = { Text(stringResource(R.string.storage_permission_explain), color = Am.Navy) },
        confirmButton = {
            TextButton({
                explain = false
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
            }) { Text(stringResource(R.string.camera_open_settings), color = Am.Navy) }
        },
        dismissButton = { TextButton({ explain = false }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
        containerColor = Am.Card,
    )
    return access
}
