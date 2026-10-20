package app.absentmotion.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class AiAction(val title: String) {
    Background("Remove Background"), Noise("Reduce Noise"), Video("Remove Background (video)");

    val offered: Boolean get() = this == Noise || BackgroundRemoval.AVAILABLE

    fun unavailable(layer: Layer?): String? = when (this) {
        Background -> if (layer?.mediaFill?.video == false) null else "Select an image layer"
        Noise -> if (layer?.kind == "audio") null else "Select an audio layer"
        Video -> if (layer?.mediaFill?.video == true) null else "Select a video layer"
    }
}

internal data class AiMedia(val file: File, val declaration: MediaDeclaration)
internal typealias AiProgress = suspend (Float, String) -> Unit

class AiTools(private val context: Context, private val state: EditorState, private val scope: CoroutineScope) {
    var running by mutableStateOf<AiAction?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var message by mutableStateOf("")
        private set
    var retry by mutableStateOf<AiAction?>(null)
        private set
    var videoSetup by mutableStateOf<Layer?>(null)
    var videoSide by mutableIntStateOf(MatteSides[1])
    private var job: Job? = null

    fun cancel() { job?.cancel(); message = "Cancelling…" }
    fun dismiss() { message = ""; retry = null }

    fun apply(action: AiAction, confirmed: Boolean = false) {
        if (running != null) return
        val layer = state.selectedId?.let(state.scene::layer)
        action.unavailable(layer)?.let { message = it; return }
        layer ?: return
        if (action == AiAction.Video && !confirmed) {
            videoSetup = layer
            return
        }
        videoSetup = null
        running = action; progress = 0f; message = "Preparing…"; retry = null
        job = scope.launch {
            var result: AiMedia? = null
            var committed = false
            try {
                val report: AiProgress = { fraction, text ->
                    withContext(Dispatchers.Main) { progress = fraction.coerceIn(0f, 1f); message = text }
                }
                if (action == AiAction.Video) {
                    val media = checkNotNull(state.scene.media(layer.mediaFill!!.uri)) { "This video is missing from the project" }
                    val matte = withContext(Dispatchers.IO) { VideoBackground.run(context, layer, media, videoSide, report).also { result = it.media } }
                    ensureActive()
                    check(state.scene.layer(layer.id) == layer) { "The layer changed; run the tool again" }
                    val groupId = Layer.newId()
                    val next = checkNotNull(state.scene.withVideoMatte(layer, matte.media.declaration, matte.frameWidth, matte.frameHeight, Layer.newId(), groupId)) { "This layer cannot be masked" }
                    state.edit { next }
                    state.selectedId = groupId
                } else {
                    val original = state.scene.media(layer.node[if (action == AiAction.Noise) "src" else "fillImage"] ?: "")
                    withContext(Dispatchers.IO) {
                        result = when (action) {
                            AiAction.Background -> BackgroundRemoval.run(context, layer.node["fillImage"]!!, original,
                                state.engine.maxPictureSize(), report)
                            else -> NoiseReduction.run(context, layer.node["src"]!!, report)
                        }
                    }
                    ensureActive()
                    check(state.scene.layer(layer.id) == layer) { "The layer changed; run the tool again" }
                    val output = result!!
                    state.edit { scene ->
                        scene.update(layer.id) { it.copy(node = it.node.with(
                            if (action == AiAction.Noise) "src" else "fillImage", output.declaration.uri)) }
                            .withMedia(output.declaration)
                    }
                }
                committed = true
                message = ""
                state.panel = EditorPanel.Tools
                state.effectBrowserOpen = false
            } catch (_: CancellationException) {
                message = "Cancelled"
            } catch (e: ModelNotReady) {
                message = e.message ?: "The background removal model is not ready yet"; retry = action
            } catch (e: Exception) {
                message = e.message ?: "Unable to process this media"; retry = action
            } catch (_: OutOfMemoryError) {
                message = if (action == AiAction.Background) "Not enough memory to process this image" else "Not enough memory to process this media"; retry = action
            } catch (_: LinkageError) {
                message = if (action == AiAction.Noise) "Noise reduction is unavailable in this build" else "Background removal is unavailable in this build"
            } finally {
                if (!committed) result?.file?.delete()
                running = null
            }
        }
    }
}

internal fun aiFile(context: Context, extension: String): File {
    val directory = File(checkNotNull(context.getExternalFilesDir(null)) { "Media storage is unavailable" }, "ai")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create AI media storage" }
    return File(directory, "${UUID.randomUUID()}.$extension")
}

internal fun aiMedia(context: Context, file: File, type: String, extra: Map<String, String>): AiMedia {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
    return AiMedia(file, MediaDeclaration(XmlNode("media", linkedMapOf(
        "uri" to uri, "filename" to file.name, "title" to file.name, "type" to type,
        "size" to "${file.length()}", "infoUpdated" to "${System.currentTimeMillis()}",
    ) + extra)))
}
