package app.absentmotion.ai

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
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

enum class AiAction(@StringRes val title: Int) {
    Background(R.string.ai_remove_background), Noise(R.string.ai_reduce_noise), Video(R.string.ai_remove_background_video);

    val offered: Boolean get() = this == Noise || BackgroundRemoval.AVAILABLE

    fun unavailable(layer: Layer?): UiText? = when (this) {
        Background -> if (layer?.mediaFill?.video == false) null else txt(R.string.ai_select_image_layer)
        Noise -> if (layer?.kind == "audio") null else txt(R.string.ai_select_audio_layer)
        Video -> if (layer?.mediaFill?.video == true) null else txt(R.string.ai_select_video_layer)
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
    var noiseSetup by mutableStateOf<Layer?>(null)
    var noiseSource by mutableStateOf(NoiseSource.Voice)
    var videoSide by mutableIntStateOf(MatteSides[1])
    private var job: Job? = null

    fun cancel() { job?.cancel(); message = AppText.get(R.string.ai_cancelling) }
    fun dismiss() { message = ""; retry = null }

    fun apply(action: AiAction, confirmed: Boolean = false) {
        if (running != null) return
        val layer = state.selectedId?.let(state.scene::layer)
        action.unavailable(layer)?.let { message = AppText.get(it); return }
        layer ?: return
        if (action == AiAction.Video && !confirmed) {
            videoSetup = layer
            return
        }
        if (action == AiAction.Noise && !confirmed) {
            noiseSetup = layer
            return
        }
        videoSetup = null
        noiseSetup = null
        running = action; progress = 0f; message = AppText.get(R.string.ai_preparing); retry = null
        job = scope.launch {
            var result: AiMedia? = null
            var committed = false
            try {
                val report: AiProgress = { fraction, text ->
                    withContext(Dispatchers.Main) { progress = fraction.coerceIn(0f, 1f); message = text }
                }
                if (action == AiAction.Video) {
                    val media = checkNotNull(state.scene.media(layer.mediaFill!!.uri)) { AppText.get(R.string.ai_video_missing) }
                    val matte = withContext(Dispatchers.IO) { VideoBackground.run(context, layer, media, videoSide, report).also { result = it.media } }
                    ensureActive()
                    check(state.scene.layer(layer.id) == layer) { AppText.get(R.string.ai_layer_changed) }
                    val groupId = Layer.newId()
                    val next = checkNotNull(state.scene.withVideoMatte(layer, matte.media.declaration, matte.frameWidth, matte.frameHeight, Layer.newId(), groupId)) { AppText.get(R.string.ai_cannot_mask) }
                    state.edit { next.update(groupId) { it.withAiMark(AiKind.Video, null, null) } }
                    state.selectedId = groupId
                } else {
                    val original = state.scene.media(layer.node[if (action == AiAction.Noise) "src" else "fillImage"] ?: "")
                    withContext(Dispatchers.IO) {
                        result = when (action) {
                            AiAction.Background -> BackgroundRemoval.run(context, layer.node["fillImage"]!!, original,
                                state.engine.maxPictureSize(), report)
                            else -> NoiseReduction.run(context, layer.node["src"]!!, noiseSource, report)
                        }
                    }
                    ensureActive()
                    check(state.scene.layer(layer.id) == layer) { AppText.get(R.string.ai_layer_changed) }
                    val output = result!!
                    val kind = if (action == AiAction.Noise) AiKind.Noise else AiKind.Background
                    val used = noiseSource
                    state.edit { scene ->
                        scene.update(layer.id) {
                            it.copy(node = it.node.with(kind.attribute!!, output.declaration.uri))
                                .withAiMark(kind, it.node[kind.attribute], output.declaration.uri, if (kind == AiKind.Noise) used.xml else null)
                        }.withMedia(output.declaration)
                    }
                }
                committed = true
                message = ""
                state.panel = EditorPanel.Tools
                state.effectBrowserOpen = false
            } catch (_: CancellationException) {
                message = AppText.get(R.string.ai_cancelled)
            } catch (e: ModelNotReady) {
                message = e.message ?: AppText.get(R.string.ai_model_not_ready_default); retry = action
            } catch (e: Exception) {
                message = e.message ?: AppText.get(R.string.ai_unable_to_process); retry = action
            } catch (_: OutOfMemoryError) {
                message = AppText.get(if (action == AiAction.Background) R.string.ai_out_of_memory_image else R.string.ai_out_of_memory_media); retry = action
            } catch (_: LinkageError) {
                message = AppText.get(if (action == AiAction.Noise) R.string.ai_noise_unavailable else R.string.ai_background_unavailable)
            } finally {
                if (!committed) result?.file?.delete()
                running = null
            }
        }
    }
}

internal fun aiFile(context: Context, extension: String): File {
    val directory = File(checkNotNull(context.getExternalFilesDir(null)) { AppText.get(R.string.ai_storage_unavailable) }, "ai")
    check(directory.isDirectory || directory.mkdirs()) { AppText.get(R.string.ai_storage_cannot_create) }
    return File(directory, "${UUID.randomUUID()}.$extension")
}

internal fun aiMedia(context: Context, file: File, type: String, extra: Map<String, String>): AiMedia {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
    return AiMedia(file, MediaDeclaration(XmlNode("media", linkedMapOf(
        "uri" to uri, "filename" to file.name, "title" to file.name, "type" to type,
        "size" to "${file.length()}", "infoUpdated" to "${System.currentTimeMillis()}",
    ) + extra)))
}
