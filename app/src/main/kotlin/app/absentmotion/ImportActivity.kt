package app.absentmotion

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

object ImportHandOff {
    private val waiting = ArrayList<String>()

    @Synchronized fun give(uris: List<String>) { waiting += uris }

    @Synchronized fun take(): List<String> = waiting.toList().also { waiting.clear() }
}

class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> streamExtras()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }.filterNot(::isOwnPrivateFile)
        if (uris.isNotEmpty()) {
            ImportHandOff.give(uris.map { it.toString() })
            val clip = ClipData.newRawUri("import", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .apply { clipData = clip },
            )
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamExtras(): List<Uri> = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()

    private fun isOwnPrivateFile(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = runCatching { java.io.File(uri.path.orEmpty()).canonicalPath }.getOrNull() ?: return true
        return listOf(applicationInfo.dataDir, applicationInfo.deviceProtectedDataDir).any { dir ->
            runCatching { java.io.File(dir).canonicalPath }.getOrNull()?.let { path == it || path.startsWith("$it/") } == true
        }
    }
}
