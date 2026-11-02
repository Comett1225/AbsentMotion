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

    fun start(uris: List<String>, launch: (grant: Boolean) -> Unit) {
        give(uris)
        try {
            try { launch(true) } catch (e: SecurityException) { launch(false) }
        } catch (t: Throwable) {
            take()
            throw t
        }
    }
}

class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra()).ifEmpty { clipUris().take(1) }
            Intent.ACTION_SEND_MULTIPLE -> streamExtras().ifEmpty { clipUris() }
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }.filterNot(::isOwnPrivateFile)
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("import", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            ImportHandOff.start(uris.map { it.toString() }) { grant ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .apply { if (grant) { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = clip } },
                )
            }
        }
        finish()
    }

    private fun clipUris(): List<Uri> = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()

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
