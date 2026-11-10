package app.absentmotion.tools

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

interface VideoFetcher {
    val available: Boolean
    val revision: StateFlow<Int>
    val downloadBytes: Long
    val av1Hardware: Boolean
    fun installed(context: Context): Boolean
    fun storedBytes(context: Context): Long
    fun remove(context: Context)
    suspend fun install(context: Context, progress: (Float) -> Unit)
    suspend fun updateIfDue(context: Context)
    suspend fun warmUp(context: Context)
    suspend fun check(context: Context, url: String): LinkInfo
    suspend fun thumbnail(urls: List<String>): ImageBitmap?
    suspend fun download(context: Context, url: String, info: LinkInfo, mode: SaveMode, choice: Int, progress: (Float) -> Unit): List<SavedMedia>
}
