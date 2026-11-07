package app.absentmotion.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withCopiedLayerIds
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.home.TemplateEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeskTemplateEmbed(state: EditorState) {
    val id = EmbedRequest.templateId ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun end() { EmbedRequest.templateId = null; state.engine.setScene(state.scene) }
    DisposableEffect(Unit) { onDispose { EmbedRequest.templateId = null } }
    Box(Modifier.fillMaxSize()) {
        TemplateEditor(id, state.engine, state.settings, onClose = ::end) { template ->
            scope.launch {
                val local = try { withContext(Dispatchers.IO) { TemplateStore(context).localize(template, ProjectStore(context).mediaDir(state.projectId)) } }
                catch (e: java.io.IOException) {
                    Toast.makeText(context, context.getString(R.string.template_add_failed), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val start = state.scene.frameStart(state.frame)
                val layerId = Layer.newId()
                val copied = local.withCopiedLayerIds().node
                val inner = copied.copy(children = copied.children.filterNot { it.name == "media" })
                val layer = Layer(XmlNode("embedScene", linkedMapOf("id" to "$layerId", "label" to local.title.ifBlank { context.getString(R.string.template) }, "startTime" to "$start", "endTime" to "${start + local.totalTime}"),
                    listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(state.scene.width / 2f, state.scene.height / 2f, 0f)))))), inner)))
                state.edit { it.add(layer, local.media) }
                state.selectedId = layerId
                end()
            }
        }
    }
}
