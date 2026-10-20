package app.absentmotion.editor

import app.absentmotion.library.MediaKeeper
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.platform.Toast
import app.absentmotion.platform.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun EditorState.keepCopy(context: Context, media: MediaDeclaration): MediaDeclaration {
    if (!settings.keepMediaCopies) return media
    val name = media.title.ifEmpty { "this file" }
    if (media.size > BIG_FILE && !store.inMediaFolder(media.uri)) Toast.makeText(context, "Copying “$name” into the project…", Toast.LENGTH_SHORT).show()
    val keeper = MediaKeeper(store, { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) })
    return try {
        withContext(Dispatchers.IO) { keeper.keep(projectId, media, scene) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MediaKeeper.NoSpace) {
        Toast.makeText(context, "Not enough free storage to keep a copy of “$name” in the project. It still uses the original file.", Toast.LENGTH_LONG).show()
        media
    } catch (e: Exception) {
        Log.w("AMStore", "cannot keep a copy of ${media.uri}", e)
        media
    }
}

private const val BIG_FILE = 32L shl 20
