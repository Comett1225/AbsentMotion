package app.absentmotion.desk

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.absentmotion.ui.home.DeskModals
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

    val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi")

    fun chooseFiles(media: Boolean, multiple: Boolean, videoOnly: Boolean = false, title: String = when { videoOnly -> "Choose a video"; media -> "Choose media"; else -> "Choose a file" }): List<File> {
        System.getProperty("am.pick")?.let { return listOf(File(it)) }
        val filters = when {
            !media -> emptyList()
            videoOnly -> listOf(DeskPicker.Filter(Strings.byName("export_kind_video") ?: "Video", videoExtensions))
            else -> listOf(DeskPicker.Filter(Strings.byName("add_tab_media") ?: "Media", mediaExtensions))
        }
        return DeskPicker.openFiles(title, filters, multiple).also { DeskLibrary.remember(it) }
    }

    fun chooseOne(media: Boolean, videoOnly: Boolean = false): Uri? = chooseFiles(media, false, videoOnly = videoOnly).firstOrNull()?.let(Uri::fromFile)

    fun chooseSave(name: String, title: String = "Save"): File? = DeskPicker.saveFile(title, name)

    fun reveal(uris: List<Uri>) {
        val file = uris.firstOrNull()?.toFile() ?: return
        DeskOs.reveal(file)
    }

    suspend fun thumbnail(uri: String, video: Boolean, size: Int): ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val file = Uri.parse(uri).toFile() ?: return@runCatching null
            if (video) app.absentmotion.desk.DeskMedia.frame(file, 0, size)?.toComposeImageBitmap()
            else android.graphics.BitmapFactory.decodeFile(file.path)?.let { android.graphics.Bitmap.createScaledBitmap(it, size, (size * it.height / it.width.coerceAtLeast(1)).coerceAtLeast(1), true) }?.image?.toComposeImageBitmap()
        }.getOrNull()
    }
}

object DeskScale {
    const val MIN = 80
    const val MAX = 150
    private val prefs get() = android.content.SharedPreferences.named("desk")
    var percent by mutableIntStateOf(prefs.getInt("uiScale", 100).coerceIn(MIN, MAX))
        private set

    fun set(value: Int) {
        percent = value.coerceIn(MIN, MAX)
        prefs.edit().putInt("uiScale", percent).apply()
    }

    fun zoomIn() = set(percent + 10)
    fun zoomOut() = set(percent - 10)
    fun reset() = set(100)
}

object DeskGlobalKeys {
    fun handle(app: DeskAppState, event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (!DeskKeys.isCommand(event)) return false
        when (event.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> DeskScale.zoomIn()
            Key.Minus, Key.NumPadSubtract -> DeskScale.zoomOut()
            Key.Zero, Key.NumPad0 -> DeskScale.reset()
            Key.Comma -> app.openSettings()
            Key.N -> if (app.editor == null && DeskModals.open == 0) app.newProjectTick++ else return false
            Key.Q -> if (!DeskOs.mac) app.requestQuit() else return false
            else -> return false
        }
        return true
    }
}
