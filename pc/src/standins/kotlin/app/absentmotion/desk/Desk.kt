package app.absentmotion.desk

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File

object DeskInfo {
    val version: String by lazy { System.getProperty("am.version") ?: app.absentmotion.BuildConfig.VERSION_NAME }
    val stamp: Long by lazy { runCatching { java.io.File(DeskInfo::class.java.protectionDomain.codeSource.location.toURI()).lastModified() }.getOrDefault(0L) }
}

object DeskBack {
    private val stack = mutableListOf<() -> Boolean>()

    @Composable
    fun Handler(enabled: Boolean, onBack: () -> Unit) {
        val latest by rememberUpdatedState(onBack)
        val on by rememberUpdatedState(enabled)
        DisposableEffect(Unit) {
            val entry: () -> Boolean = { if (on) { latest(); true } else false }
            synchronized(stack) { stack += entry }
            onDispose { synchronized(stack) { stack -= entry } }
        }
    }

    fun back(): Boolean = synchronized(stack) { stack.toList() }.asReversed().any { it() }
}

object DeskFiles {
    var window: java.awt.Frame? = null
    val mediaExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "svg", "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac")

    fun chooseFiles(media: Boolean, multiple: Boolean, title: String = if (media) "Choose media" else "Choose a file"): List<File> {
        val dialog = java.awt.FileDialog(window, title, java.awt.FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        if (media) dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in mediaExtensions }
        dialog.isVisible = true
        return dialog.files.orEmpty().toList().also { DeskLibrary.remember(it) }
    }

    fun chooseOne(media: Boolean): Uri? = chooseFiles(media, false).firstOrNull()?.let(Uri::fromFile)

    fun chooseSave(name: String, title: String = "Save"): File? {
        val dialog = java.awt.FileDialog(window, title, java.awt.FileDialog.SAVE)
        dialog.file = name
        dialog.isVisible = true
        return dialog.files.orEmpty().firstOrNull()
    }

    fun reveal(uris: List<Uri>) {
        val file = uris.firstOrNull()?.toFile() ?: return
        runCatching {
            if (System.getProperty("os.name").lowercase().contains("mac")) ProcessBuilder("open", "-R", file.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(file.parentFile)
        }
    }

    suspend fun thumbnail(uri: String, video: Boolean, size: Int): ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val file = Uri.parse(uri).toFile() ?: return@runCatching null
            if (video) app.absentmotion.desk.DeskMedia.frame(file, 0, size)?.toComposeImageBitmap()
            else android.graphics.BitmapFactory.decodeFile(file.path)?.let { android.graphics.Bitmap.createScaledBitmap(it, size, (size * it.height / it.width.coerceAtLeast(1)).coerceAtLeast(1), true) }?.image?.toComposeImageBitmap()
        }.getOrNull()
    }
}

object DeskSpeech {
    suspend fun voices(): List<app.absentmotion.ui.editor.SpeechVoice> = emptyList()
    fun preview(text: String, voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float) {}
    suspend fun toFile(text: String, voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float): Uri = throw UnsupportedOperationException("Text to speech is not available on this computer yet")
}
