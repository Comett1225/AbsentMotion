package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.library.MediaKeeper
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.platform.Toast
import app.absentmotion.platform.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

suspend fun EditorState.keepCopy(context: Context, media: MediaDeclaration): MediaDeclaration = keepCopy(context, settings, store, projectId, media, scene)

suspend fun keepCopy(context: Context, settings: AppSettings, store: ProjectStore, projectId: String, media: MediaDeclaration, scene: Scene?): MediaDeclaration {
    if (!settings.keepMediaCopies) return media
    val name = media.title.ifEmpty { context.getString(R.string.edit_keep_this_file) }
    if (media.size > BIG_FILE && !store.inMediaFolder(media.uri)) Toast.makeText(context, context.getString(R.string.edit_keep_copying, name), Toast.LENGTH_SHORT).show()
    val keeper = MediaKeeper(store, { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) })
    return try {
        withContext(Dispatchers.IO) { keeper.keep(projectId, media, scene) { !isActive } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MediaKeeper.NoSpace) {
        Toast.makeText(context, context.getString(R.string.edit_keep_no_space, name), Toast.LENGTH_LONG).show()
        media
    } catch (e: Exception) {
        Log.w("AMStore", "cannot keep a copy of ${media.uri}", e)
        Toast.makeText(context, context.getString(R.string.edit_keep_failed, name), Toast.LENGTH_LONG).show()
        media
    }
}

private const val BIG_FILE = 32L shl 20
