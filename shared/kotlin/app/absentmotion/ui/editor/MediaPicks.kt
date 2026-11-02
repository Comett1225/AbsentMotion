package app.absentmotion.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import app.absentmotion.R
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.editor.keepCopy
import app.absentmotion.i18n.AppText

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

    private val pictures = mutableStateListOf<Pair<Uri, Long>>()
    private val sounds = mutableStateListOf<Pair<Uri, Long>>()

    val waiting: Int get() = pictures.size + sounds.size

    fun addPictures(uris: List<Uri>) { pictures += uris.map { it to System.currentTimeMillis() } }

    fun addSounds(uris: List<Uri>) { sounds += uris.map { it to System.currentTimeMillis() } }

    suspend fun deliver(state: EditorState, context: Context) {
        val now = System.currentTimeMillis()
        val pictures = take(pictures, now)
        val sounds = take(sounds, now)
        if (target != null && target != state.projectId) return
        if (pictures.isNotEmpty()) state.addPictures(context, pictures)
        if (sounds.isNotEmpty()) state.addSounds(context, sounds)
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
            val media = audioDeclaration(context, uri)?.let { keepCopy(context, it) }
            if (media == null || media.duration <= 0) Toast.makeText(context, AppText.get(R.string.library_err_unsupported_type), Toast.LENGTH_LONG).show()
            else addAudio(media)
        }
    } finally { addingMedia = false }
}

private fun keepAccess(context: Context, uris: List<Uri>) =
    uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
