package app.absentmotion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.media.imageDeclaration
import app.absentmotion.media.videoDeclaration
import app.absentmotion.ui.editor.MediaPicks
import app.absentmotion.ui.home.BigButton
import app.absentmotion.ui.home.DefaultNames
import app.absentmotion.ui.home.Form
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.home.mediaProject
import app.absentmotion.ui.home.rememberFileBitmap
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.absentmotion.ui.home.DialogPill

internal enum class SharedMediaKind { Video, Picture, Sound }

private val projectExtensions = setOf("absproj", "amproj", "fla", "xml", "zip")

internal fun sharedMediaKind(name: String?, mime: String?): SharedMediaKind? = when {
    name?.substringAfterLast('.', "")?.lowercase() in projectExtensions -> null
    mime?.startsWith("video/", ignoreCase = true) == true -> SharedMediaKind.Video
    mime?.startsWith("image/", ignoreCase = true) == true -> SharedMediaKind.Picture
    mime?.startsWith("audio/", ignoreCase = true) == true -> SharedMediaKind.Sound
    else -> null
}

internal class SharedMedia(val uri: Uri, val name: String, val mime: String, val kind: SharedMediaKind)

private val mediaExtensions = mapOf(
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska", "webm" to "video/webm", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif", "heic" to "image/heic", "bmp" to "image/bmp", "svg" to "image/svg+xml",
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac",
)

internal fun sharedMime(name: String, reported: String?): String? {
    val mime = when (val type = reported?.lowercase()) {
        "application/ogg" -> "audio/ogg"
        "application/x-flac" -> "audio/flac"
        else -> type
    }
    return mime?.takeIf { sharedMediaKind(null, it) != null } ?: mediaExtensions[name.substringAfterLast('.', "").lowercase()]
}

internal fun Context.readSharedMedia(uri: Uri): SharedMedia? {
    val reported = runCatching { contentResolver.getType(uri) }.getOrNull()
    val name = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    val mime = sharedMime(name, reported) ?: return null
    return sharedMediaKind(name, mime)?.let { SharedMedia(uri, name, mime, it) }
}

internal fun queueSharedMedia(project: String, media: List<SharedMedia>) {
    val (sounds, pictures) = media.partition { it.kind == SharedMediaKind.Sound }
    MediaPicks.addShared(project, (pictures + sounds).map { it.uri to it.mime })
}

internal suspend fun createSharedProject(context: Context, store: ProjectStore, settings: AppSettings, media: List<SharedMedia>): String? {
    val first = media.firstOrNull { it.kind != SharedMediaKind.Sound } ?: media.first()
    val title = first.name.substringBeforeLast('.').take(60)
    val scene = if (first.kind == SharedMediaKind.Sound || first.mime.equals("image/svg+xml", ignoreCase = true)) {
        Form(settings.newProject, emptyList(), DefaultNames(context), false).apply { name = title }.scene()
    } else {
        val visual = if (first.kind == SharedMediaKind.Video) videoDeclaration(context, first.uri, first.mime) else imageDeclaration(context, first.uri)
        if (visual == null || first.kind == SharedMediaKind.Video && visual.duration <= 0) return null
        mediaProject(title, visual)
    }
    return withContext(Dispatchers.IO) { store.create(scene) }
}

@Composable
internal fun SharedMediaDialog(media: List<SharedMedia>, store: ProjectStore, changes: Int, onNew: () -> Unit, onProject: (String) -> Unit, onCancel: () -> Unit) {
    val projects by produceState(emptyList<ProjectInfo>(), store, changes) {
        value = withContext(Dispatchers.IO) { store.list().filterNot { it.isElement }.sortedByDescending { it.modified } }
    }
    val count = when (media.first().kind.takeIf { kind -> media.all { it.kind == kind } }) {
        SharedMediaKind.Video -> R.plurals.share_videos
        SharedMediaKind.Picture -> R.plurals.share_pictures
        SharedMediaKind.Sound -> R.plurals.share_sounds
        null -> R.plurals.share_files
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.share_add_to_project), color = Am.Navy) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pluralStringResource(count, media.size, media.size), color = Am.Navy)
                BigButton(stringResource(R.string.home_new_project), Modifier.fillMaxWidth(), HomeIcons.Plus, onClick = onNew)
                if (projects.isNotEmpty()) {
                    Text(stringResource(R.string.share_or_add_to), color = Am.Navy)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(projects, key = { it.id }) { project ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onProject(project.id) }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                rememberFileBitmap(project.thumbnail, project.modified)?.let { Image(it, null, Modifier.size(36.dp), contentScale = ContentScale.Fit) }
                                Text(project.title, color = Am.Navy, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { DialogPill(stringResource(R.string.cancel), primary = false, onClick = onCancel) },
        containerColor = Am.Card,
    )
}
