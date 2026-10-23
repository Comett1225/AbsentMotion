package app.absentmotion.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

internal class StorageAccess(private val sdk: Int, private val granted: () -> Boolean, private val ask: () -> Unit) {
    private var asked = false
    private var again: (() -> Unit)? = null

    fun ready(retry: () -> Unit): Boolean {
        if (sdk >= 29 || asked || granted()) return true
        asked = true
        again = retry
        ask()
        return false
    }

    fun answered() {
        again?.let { again = null; it() }
    }
}

@Composable
internal fun rememberStorageAccess(): StorageAccess {
    val context = LocalContext.current
    val launcher = remember { arrayOfNulls<ActivityResultLauncher<String>>(1) }
    val access = remember {
        StorageAccess(
            Build.VERSION.SDK_INT,
            { context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED },
            { launcher[0]?.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
        )
    }
    launcher[0] = rememberLauncherForActivityResult(RequestPermission()) { access.answered() }
    return access
}
