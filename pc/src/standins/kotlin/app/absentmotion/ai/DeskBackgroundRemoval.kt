package app.absentmotion.ai

import android.content.Context
import app.absentmotion.model.MediaDeclaration

internal class ModelNotReady : Exception("Background removal is not available on this computer yet")

internal object BackgroundRemoval {
    val AVAILABLE = false
    suspend fun run(context: Context, uri: String, original: MediaDeclaration?, limit: Int, cutout: Cutout = Cutout(), progress: AiProgress): AiMedia = throw ModelNotReady()
    suspend fun <T> withFrameMatte(context: Context, block: suspend (suspend (IntArray, Int, Int) -> ByteArray) -> T): T = throw ModelNotReady()
}
