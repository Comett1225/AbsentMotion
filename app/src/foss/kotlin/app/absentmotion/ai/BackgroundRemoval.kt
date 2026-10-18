package app.absentmotion.ai

import android.content.Context
import app.absentmotion.model.MediaDeclaration

internal class ModelNotReady : Exception("Background removal is not available in this build.")

internal object BackgroundRemoval {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun run(context: Context, uri: String, original: MediaDeclaration?, limit: Int, progress: AiProgress): AiMedia =
        throw ModelNotReady()
}
