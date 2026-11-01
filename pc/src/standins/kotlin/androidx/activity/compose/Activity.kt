package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> {
    val latest by rememberUpdatedState(onResult)
    val shown by rememberUpdatedState(contract)
    return remember { ActivityResultLauncher { input -> val result = shown.desktop(input); javax.swing.SwingUtilities.invokeLater { latest(result) } } }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = app.absentmotion.desk.DeskBack.Handler(enabled, onBack)

class DeskActivity { fun shouldShowRequestPermissionRationale(permission: String) = false; fun recreate() {}; fun finish() {} }

val LocalActivity = staticCompositionLocalOf<DeskActivity?> { DeskActivity() }
