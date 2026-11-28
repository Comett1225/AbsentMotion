package app.absentmotion.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.AppSettings
import app.absentmotion.library.CommunityClient
import app.absentmotion.library.PackUpdate
import app.absentmotion.library.PackUpdates
import app.absentmotion.ui.editor.effects.AddedEffectDialogs
import app.absentmotion.ui.editor.effects.AddedEffectImport
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PackUpdateAlert(settings: AppSettings, engine: (() -> EngineThread)?) {
    if (engine == null || !settings.autoUpdateCheck) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember(context) { AddedEffectImport(context, engine) }
    var update by remember { mutableStateOf<PackUpdate?>(null) }
    LaunchedEffect(Unit) {
        val running = engine()
        running.awaitEffects()
        update = withContext(Dispatchers.IO) { runCatching { PackUpdates.check(context, running.effects.value) }.getOrNull() }
    }
    val unreachable = stringResource(R.string.added_effect_community_unreachable)
    update?.let { found ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text(stringResource(R.string.pack_update_title, found.item.title), color = Am.Navy) },
            text = { Text(pluralStringResource(R.plurals.pack_update_body, found.changed.size, found.changed.size), color = Am.HomeMid) },
            confirmButton = {
                DialogPill(stringResource(R.string.added_effect_update)) {
                    update = null
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { CommunityClient(context.cacheDir).download(found.item) }
                            importer.pickCommunity(file, found.item, found.changed.map { it.id }.toSet())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, unreachable, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            dismissButton = { DialogPill(stringResource(R.string.update_later), primary = false) { PackUpdates.skip(context, found.item); update = null } },
            containerColor = Am.Card,
        )
    }
    AddedEffectDialogs(importer)
}
