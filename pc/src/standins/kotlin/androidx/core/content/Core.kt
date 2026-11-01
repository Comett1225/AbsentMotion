package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File

object FileProvider {
    @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.fromFile(file)
}

object ContextCompat {
    @JvmStatic fun getMainExecutor(context: Context): java.util.concurrent.Executor = java.util.concurrent.Executor { javax.swing.SwingUtilities.invokeLater(it) }
    @JvmStatic fun startForegroundService(context: Context, intent: android.content.Intent) {}
    @JvmStatic fun checkSelfPermission(context: Context, permission: String) = 0
    @JvmStatic fun getColor(context: Context, id: Int) = 0
}
