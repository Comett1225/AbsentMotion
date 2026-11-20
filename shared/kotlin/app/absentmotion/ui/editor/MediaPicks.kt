package app.absentmotion.ui.editor

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import app.absentmotion.R
import app.absentmotion.audio.TextBlips
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.model.formatFloats
import app.absentmotion.model.effects
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.editor.keepCopy
import app.absentmotion.i18n.AppText
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri

internal object MediaPicks {
    private const val FRESH_MS = 120_000L

    private var owner: Any? = null
    private var launchVisual: () -> Unit = {}
    private var launchFiles: () -> Unit = {}
    private var launchSounds: () -> Unit = {}
    private var currentProject: () -> String? = { null }
    private var target: String? = null

    fun install(owner: Any, visual: () -> Unit, files: () -> Unit, sounds: () -> Unit, project: () -> String?) {
        this.owner = owner
        launchVisual = visual
        launchFiles = files
        launchSounds = sounds
        currentProject = project
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        launchVisual = {}
        launchFiles = {}
        launchSounds = {}
        currentProject = { null }
    }

    fun openVisual() { target = currentProject(); launchVisual() }

    fun openFiles() { target = currentProject(); launchFiles() }

    fun openSounds() { target = currentProject(); launchSounds() }

    fun forgetTarget() { target = null }

    private val pictures = mutableStateListOf<Pair<Uri, Long>>()
    private val sounds = mutableStateListOf<Pair<Uri, Long>>()
    private val shared = mutableStateListOf<SharedPicks>()

    private class SharedPicks(val project: String, val media: List<Pair<Uri, String>>)

    val waiting: Int get() = pictures.size + sounds.size + shared.size

    fun addPictures(uris: List<Uri>) { pictures += uris.map { it to System.currentTimeMillis() } }

    fun addSounds(uris: List<Uri>) { sounds += uris.map { it to System.currentTimeMillis() } }

    fun addShared(project: String, media: List<Pair<Uri, String>>) {
        shared += SharedPicks(project, media)
    }

    suspend fun deliver(state: EditorState, context: Context) {
        val now = System.currentTimeMillis()
        val pictures = take(pictures, now)
        val sounds = take(sounds, now)
        if (target == null || target == state.projectId) {
            if (pictures.isNotEmpty()) state.addPictures(context, pictures)
            if (sounds.isNotEmpty()) state.addSounds(context, sounds)
        }
        while (shared.isNotEmpty()) {
            val pick = shared.removeAt(0)
            if (pick.project != state.projectId) continue
            state.addingMedia = true
            try {
                for ((uri, mime) in pick.media) state.addPicked(context, uri, alwaysCopy = true, mime = mime)
            } finally { state.addingMedia = false }
        }
    }

    private fun take(list: MutableList<Pair<Uri, Long>>, now: Long): List<Uri> =
        list.toList().also { list.clear() }.filter { now - it.second <= FRESH_MS }.map { it.first }
}

internal suspend fun EditorState.addPictures(context: Context, uris: List<Uri>) {
    keepAccess(context, uris)
    addingMedia = true
    try { uris.forEach { addPicked(context, it) } } finally { addingMedia = false }
}

internal suspend fun EditorState.addSounds(context: Context, uris: List<Uri>) {
    keepAccess(context, uris)
    addingMedia = true
    try {
        for (uri in uris) {
            val declared = audioDeclaration(context, uri)
            val media = if (declared == null) null else keepCopy(context, declared)
            if (media == null || media.duration <= 0) Toast.makeText(context, mediaTroubleOf(context, uri) ?: AppText.get(R.string.library_err_unsupported_type), Toast.LENGTH_LONG).show()
            else addAudio(media)
        }
    } finally { addingMedia = false }
}

internal suspend fun EditorState.keepPickedCopy(context: Context, media: MediaDeclaration, alwaysCopy: Boolean): MediaDeclaration? =
    keepCopy(context, media, always = alwaysCopy).takeIf { !alwaysCopy || store.inMediaFolder(it.uri) }

internal suspend fun EditorState.pickBlipSound(context: Context, uri: Uri, layerId: Long, effect: Int) {
    keepAccess(context, listOf(uri))
    val media = audioDeclaration(context, uri)?.let { keepCopy(context, it) }?.takeIf { it.duration > 0 }
    if (media == null) {
        Toast.makeText(context, mediaTroubleOf(context, uri) ?: AppText.get(R.string.library_err_unsupported_type), Toast.LENGTH_LONG).show()
        return
    }
    if (scene.layer(layerId)?.effects?.getOrNull(effect)?.get("id") != "absent.textblips") return
    edit { scene ->
        scene.withMedia(media).update(layerId, sampleMs) { layer ->
            layer.withEffect(effect) { it.with("src", media.uri).withEffectProperty("voice", "float", formatFloats(floatArrayOf(TextBlips.CUSTOM.toFloat())), layer.t, layer.snap) }
        }
    }
}

private fun EditorState.keepAccess(context: Context, uris: List<Uri>) {
    if (!settings.keepMediaCopies) uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
}
