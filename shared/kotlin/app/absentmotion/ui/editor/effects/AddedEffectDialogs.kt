package app.absentmotion.ui.editor.effects

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.effects.sameAddedAuthor
import app.absentmotion.effects.AddedEffect
import app.absentmotion.effects.AddedEffectAction
import app.absentmotion.effects.AddedEffectPrefix
import app.absentmotion.effects.AddedEffectSource
import app.absentmotion.effects.AddedEffectUse
import app.absentmotion.effects.addedEffectAction
import app.absentmotion.effects.addedEffectUses
import app.absentmotion.effects.readAddedEffectEntries
import app.absentmotion.effects.readBundledAddedEffect
import app.absentmotion.effects.AddedEffectPreview
import app.absentmotion.effects.AddedEffectStore
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.readAddedEffectUri
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.formatBytes
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.text
import app.absentmotion.i18n.txt
import app.absentmotion.library.CommunityClient
import app.absentmotion.library.CommunityDamagedException
import app.absentmotion.library.communityDownloadSource
import app.absentmotion.library.CommunityFeed
import app.absentmotion.library.CommunityIndex
import app.absentmotion.library.CommunityItem
import app.absentmotion.library.CommunityItemUnavailable
import app.absentmotion.library.CommunityKind
import app.absentmotion.library.CommunityWriteException
import app.absentmotion.library.ImportReport
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.effectPacks
import app.absentmotion.model.Scene
import app.absentmotion.platform.ActivityResultContracts
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Context
import app.absentmotion.platform.MobileDataRefused
import app.absentmotion.platform.Uri
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.platform.rememberLauncherForActivityResult
import androidx.compose.ui.res.stringResource
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateSource
import app.absentmotion.ui.home.sessionAllowed
import app.absentmotion.ui.home.sessionIndex
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import app.absentmotion.ui.home.DialogPill

internal sealed interface ProjectEffectPrompt {
    data class Bundle(val names: List<String>) : ProjectEffectPrompt
    data class Missing(val effects: List<AddedEffectUse>, val look: Boolean, val updates: Set<String>) : ProjectEffectPrompt
    data object Consent : ProjectEffectPrompt
    data class Packs(val packs: Map<CommunityItem, List<AddedEffectUse>>, val update: Boolean) : ProjectEffectPrompt
    data class Progress(val label: UiText, val cancellable: Boolean = true) : ProjectEffectPrompt
    data class Notice(val messages: List<UiText>, val retry: Boolean = false, val canOpen: Boolean = false) : ProjectEffectPrompt
}

internal enum class ProjectEffectAnswer { Add, Look, Continue, Retry, Open }

internal class AddedEffectImport(private val context: Context, private val getEngine: () -> EngineThread) {
    private val store = AddedEffectStore(context)
    private val imports = Mutex()
    private val projectOpen = Mutex()
    private val reviewedImports = mutableMapOf<String, List<AddedEffectUse>>()
    private var projectAnswer: CompletableDeferred<ProjectEffectAnswer>? = null
    var projectPrompt by mutableStateOf<ProjectEffectPrompt?>(null)
        private set
    var handlingProject by mutableStateOf(false)
        private set
    var previews by mutableStateOf(emptyList<AddedEffectPreview>())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiText?>(null)
        private set
    var removing by mutableStateOf<EffectDefinition?>(null)
    val engine get() = getEngine()

    suspend fun showInCategory(id: String, show: Boolean) =
        engine.updateAddedEffectPlacement { it.placeAddedEffect(store, id, show) }

    suspend fun showCreatorCategory(slug: String, show: Boolean) =
        engine.updateAddedEffectPlacement { it.placeAddedCreator(store, slug, show) }

    suspend fun pick(uris: List<Uri>) {
        if (uris.isEmpty()) return
        present(withContext(Dispatchers.IO) { uris.mapNotNull { readAddedEffectUri(context, it, fromPicker = true) } })
    }

    suspend fun pickCommunity(file: File, item: CommunityItem) {
        val entries = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { readAddedEffectEntries(item.file.substringAfterLast('/'), it, context.cacheDir) }
            }.getOrElse { listOf(item.title.ifBlank { item.name } to Result.failure(it)) }
        }
        val source = AddedEffectSource(item.name, item.sha256)
        val rejected = entries.filter { it.second.isFailure }.map { it.first }
        present(entries.map { (_, effect) -> effect.map { listOf(it.from(source)) } }, skipped(rejected))
    }

    suspend fun present(files: List<Result<List<AddedEffect>>>, problem: UiText? = null) = imports.withLock {
        if (files.isEmpty()) return@withLock
        val effects = files.flatMap { it.getOrNull().orEmpty() }
        busy = true
        try {
            if (effects.isNotEmpty()) {
                engine.awaitEffects()
                previews = previews + preview(effects)
            }
            if (files.any { it.isFailure }) error = problem ?: txt(R.string.added_effect_bad_file)
        } finally {
            busy = false
        }
    }

    fun dismissPreview() {
        previews = emptyList()
        error = null
    }

    fun dismissError() { error = null }

    suspend fun install() = imports.withLock {
        val working = previews.filter { it.works }
        if (working.isEmpty()) return@withLock
        busy = true
        error = null
        try {
            if (installWorking(working)) {
                previews = emptyList()
                val missing = projectPrompt as? ProjectEffectPrompt.Missing
                if (missing != null) {
                    val remaining = missing.effects.filter(::isMissing)
                    if (remaining.isEmpty()) answer(ProjectEffectAnswer.Continue)
                    else projectPrompt = missingPrompt(remaining, missing.look)
                }
            } else {
                error = txt(R.string.added_effect_save_failed)
            }
        } finally {
            busy = false
        }
    }

    suspend fun remove(effect: EffectDefinition) = imports.withLock {
        try {
            if (!engine.updateAddedEffects(store, remove = effect)) error = txt(R.string.added_effect_remove_failed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = txt(R.string.added_effect_remove_failed)
        }
    }

    suspend fun confirmMissing(scene: Scene, projectId: String) = projectOpen.withLock {
        val uses = scene.addedEffectUses()
        if (reviewedImports.remove(projectId) != uses) review(uses, emptyMap())
    }

    suspend fun confirmImported(reports: List<ImportReport>, store: ProjectStore) = projectOpen.withLock {
        val projects = withContext(Dispatchers.IO) { reports.mapNotNull { report -> store.load(report.id)?.let { report.id to it } } }
        val bundled = reports.firstOrNull()?.bundledEffects.orEmpty()
        val uses = projects.flatMap { (_, scene) -> scene.addedEffectUses() }.groupBy { it.id }
            .values.map { entries -> entries.maxBy { it.version } }
        review(uses, bundled)
        for ((id, scene) in projects) {
            val refs = scene.addedEffectUses()
            if (refs.any(::isMissing)) reviewedImports[id] = refs
        }
    }

    private suspend fun review(uses: List<AddedEffectUse>, bundled: Map<String, ByteArray>) {
        if (uses.isEmpty() && bundled.isEmpty()) return
        handlingProject = true
        try {
            engine.awaitEffects()
            val declined = offerBundle(bundled)
            val remaining = uses.filter { isMissing(it) && it.id !in declined }
            if (remaining.isNotEmpty()) offerMissing(remaining)
        } finally {
            projectPrompt = null
            handlingProject = false
        }
    }

    private suspend fun offerBundle(bundled: Map<String, ByteArray>): Set<String> {
        if (bundled.isEmpty()) return emptySet()
        val entries = withContext(Dispatchers.IO) { bundled.map { (entry, bytes) -> readBundledAddedEffect(entry, bytes) } }
        val candidates = entries.filter { (_, result) -> result.getOrNull()?.let(::isNewer) ?: true }
        if (candidates.isEmpty()) return emptySet()
        if (ask(ProjectEffectPrompt.Bundle(candidates.map { it.first })) != ProjectEffectAnswer.Add) {
            return candidates.mapNotNullTo(hashSetOf()) { (_, result) -> result.getOrNull()?.definition?.id }
        }
        val failures = saveEntries(candidates)
        if (failures.isNotEmpty()) ask(ProjectEffectPrompt.Notice(failures))
        return emptySet()
    }

    private suspend fun offerMissing(uses: List<AddedEffectUse>) {
        while (!sessionAllowed && sessionIndex == null) {
            if (ask(missingPrompt(uses, look = true)) != ProjectEffectAnswer.Look) return
            if (ask(ProjectEffectPrompt.Consent) == ProjectEffectAnswer.Add) sessionAllowed = true
        }
        val cached = sessionIndex
        var current = cached ?: fetchIndex() ?: return
        if (cached != null && current.effectPacks(uses).values.flatten().mapTo(hashSetOf()) { it.id }.size < uses.map { it.id }.toSet().size) {
            current = fetchIndex() ?: current
        }
        val matching = current.effectPacks(uses)
        val applicable = matching.values.flatten().mapTo(hashSetOf()) { it.id }
        val unresolved = uses.filter { it.id !in applicable }
        val blocked = unresolved.filter { use ->
            current.unavailableEffects.any { item -> item.matches(use) || item.name == use.pack }
        }
        val unavailable = unresolved.filter { use ->
            use.pack != null && (current.items + current.unavailableEffects).none { it.kind == CommunityKind.Effect && it.name == use.pack }
        }
        val notes = buildList {
            if (current.tooNew) add(txt(R.string.added_effect_community_update))
            if (blocked.isNotEmpty()) add(packMessage(blocked.joinToString(", ") { it.name }, R.string.added_effect_community_update))
            if (unavailable.isNotEmpty()) add(packMessage(unavailable.joinToString(", ") { it.name }, R.string.added_effect_community_unavailable))
        }
        if (notes.isNotEmpty()) ask(ProjectEffectPrompt.Notice(notes))
        if (matching.isNotEmpty()) {
            val offered = ProjectEffectPrompt.Packs(matching, uses.any(::hasInstalled))
            if (ask(offered) != ProjectEffectAnswer.Add || !installPacks(matching)) return
        }
        val remaining = uses.filter(::isMissing)
        if (remaining.isNotEmpty()) ask(missingPrompt(remaining, look = false))
    }

    private suspend fun fetchIndex(): CommunityIndex? =
        communityRequest(txt(R.string.about_loading)) { it.index() }?.also { sessionIndex = it }

    private suspend fun installPacks(packs: Map<CommunityItem, List<AddedEffectUse>>): Boolean {
        val index = fetchIndex() ?: return false
        for ((old, uses) in packs) {
            val label = old.title.ifBlank { old.name }
            val item = index.items.firstOrNull { it.kind == CommunityKind.Effect && it.name == old.name && uses.any(it::matches) }
            if (item != null && item.sha256 != old.sha256 && ask(ProjectEffectPrompt.Packs(mapOf(item to uses), uses.any(::hasInstalled))) != ProjectEffectAnswer.Add) continue
            if (item == null) {
                val reason = if (index.tooNew || index.unavailableEffects.any { it.name == old.name }) R.string.added_effect_community_update
                    else R.string.added_effect_community_unavailable
                ask(ProjectEffectPrompt.Notice(listOf(packMessage(label, reason))))
                continue
            }
            val progress = txt(R.string.added_effect_community_downloading, label)
            val file = try {
                communityRequest(progress, label, communityDownloadSource(item)) { it.download(item) } ?: return false
            } catch (_: CommunityItemUnavailable) {
                continue
            }
            val source = AddedEffectSource(item.name, item.sha256)
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    file.inputStream().use { readAddedEffectEntries(item.file.substringAfterLast('/'), it, context.cacheDir) }
                }.getOrElse {
                    return@withContext listOf(label to Result.failure<AddedEffect>(it))
                }.map { (name, effect) -> name to effect.map { it.from(source) } }
            }
            val supplied = entries.mapNotNullTo(hashSetOf()) { (_, effect) -> effect.getOrNull()?.definition?.id }
            if (uses.none { it.id in supplied }) {
                ask(ProjectEffectPrompt.Notice(listOf(packMessage(label, R.string.added_effect_community_unavailable))))
                continue
            }
            while (true) {
                val failures = saveEntries(entries)
                if (failures.isEmpty()) break
                val retry = failures.any { it.id == R.string.added_effect_save_failed }
                val answer = ask(ProjectEffectPrompt.Notice(failures, retry = retry, canOpen = retry))
                if (answer == ProjectEffectAnswer.Open) return false
                if (answer != ProjectEffectAnswer.Retry) break
            }
        }
        return true
    }

    private suspend fun <T> communityRequest(progress: UiText, label: String? = null, source: UpdateSource = CommunityFeed.source, load: (CommunityClient) -> T): T? {
        while (true) {
            val result = download(progress, source, load) ?: return null
            if (result.isSuccess) return result.getOrThrow()
            val failure = result.exceptionOrNull()
            val reason = when (failure) {
                is MobileDataRefused -> R.string.mobile_data_refused
                is CommunityDamagedException -> R.string.community_download_damaged
                is CommunityItemUnavailable -> R.string.added_effect_community_unavailable
                is CommunityWriteException -> R.string.added_effect_save_failed
                else -> R.string.added_effect_community_unreachable
            }
            val message = label?.let { packMessage(it, reason) } ?: txt(reason)
            val retry = failure !is CommunityItemUnavailable
            val answer = ask(ProjectEffectPrompt.Notice(listOf(message), retry = retry, canOpen = retry))
            if (answer == ProjectEffectAnswer.Retry) continue
            if (!retry && label != null) throw CommunityItemUnavailable()
            return null
        }
    }

    private suspend fun <T> download(progress: UiText, source: UpdateSource, load: (CommunityClient) -> T): Result<T>? = supervisorScope {
        val http = TrustedHttp(source)
        val stop = CompletableDeferred<ProjectEffectAnswer>()
        projectAnswer = stop
        projectPrompt = ProjectEffectPrompt.Progress(progress)
        val work = async {
            try {
                Result.success(runInterruptible(Dispatchers.IO) { load(CommunityClient(context.cacheDir) { http }) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        try {
            select {
                stop.onAwait { null }
                work.onAwait { it }
            }
        } finally {
            http.abort()
            work.cancel()
            projectAnswer = null
            projectPrompt = null
        }
    }

    private suspend fun saveEntries(entries: List<Pair<String, Result<AddedEffect>>>): List<UiText> = imports.withLock {
        projectPrompt = ProjectEffectPrompt.Progress(txt(R.string.community_adding), cancellable = false)
        try {
            val candidates = entries.mapNotNull { (_, entry) -> entry.getOrNull()?.takeIf(::isNewer) }
            val tested = preview(candidates)
            val broken = tested.filterNot { it.works }.map { it.effect.definition.name }.distinct()
            val rejected = entries.filter { it.second.isFailure }.map { it.first }.distinct()
            val working = tested.filter { it.works }.groupBy { it.effect.definition.id }
                .values.map { versions -> versions.maxBy { it.effect.definition.addedVersion } }
            val saved = installWorking(working, newerOnly = true)
            buildList {
                skipped(rejected)?.let(::add)
                if (broken.isNotEmpty()) add(packMessage(broken.joinToString(", "), R.string.added_effect_does_not_work))
                if (!saved) add(txt(R.string.added_effect_save_failed))
            }
        } finally {
            projectPrompt = null
        }
    }

    private suspend fun preview(effects: List<AddedEffect>): List<AddedEffectPreview> = try {
        engine.previewAddedEffects(context, effects)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        effects.map { AddedEffectPreview(it, false, null) }
    }

    private suspend fun installWorking(working: List<AddedEffectPreview>, newerOnly: Boolean = false): Boolean = try {
        working.isEmpty() || engine.updateAddedEffects(store, add = working, newerOnly = newerOnly)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun isMissing(use: AddedEffectUse) = (engine.effects.value.find(use.id)?.addedVersion ?: 0L) < use.version
    private fun hasInstalled(use: AddedEffectUse) = engine.effects.value.find(use.id) != null
    private fun missingPrompt(effects: List<AddedEffectUse>, look: Boolean) =
        ProjectEffectPrompt.Missing(effects, look, effects.filter(::hasInstalled).mapTo(hashSetOf()) { it.id })
    private fun isNewer(effect: AddedEffect) =
        effect.definition.addedVersion > (engine.effects.value.find(effect.definition.id)?.addedVersion ?: 0L)

    private suspend fun ask(prompt: ProjectEffectPrompt): ProjectEffectAnswer {
        val decision = CompletableDeferred<ProjectEffectAnswer>()
        projectAnswer = decision
        projectPrompt = prompt
        return try {
            decision.await()
        } finally {
            projectAnswer = null
            projectPrompt = null
        }
    }

    fun answer(choice: ProjectEffectAnswer) { projectAnswer?.complete(choice) }
}

private fun CommunityItem.matches(use: AddedEffectUse) =
    contents.any { it.id == use.id.removePrefix(AddedEffectPrefix) && it.version >= use.version }

private fun packMessage(label: String, reason: Int) = txt(R.string.added_effect_community_message, label, txt(reason))

private fun skipped(names: List<String>) = names.takeIf { it.isNotEmpty() }?.let {
    txt(R.string.added_effect_skipped, it.joinToString(", "))
}

@Composable
internal fun rememberAddedEffectPicker(importer: AddedEffectImport): () -> Unit {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { importer.pick(uris) }
    }
    return { picker.launch(arrayOf("*/*")) }
}

@Composable
internal fun AddedEffectDialogs(importer: AddedEffectImport, showImport: Boolean = true, pickFile: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val pick = pickFile ?: rememberAddedEffectPicker(importer)
    val previews = importer.previews
    val error = importer.error
    val showFiles = showImport || importer.projectPrompt is ProjectEffectPrompt.Missing
    if (showFiles && previews.isNotEmpty()) {
        val catalog by importer.engine.effects.collectAsState()
        val working = previews.filter { it.works }
        val actions = working.map { catalog.action(it.effect) }.distinct()
        val action = listOf(AddedEffectAction.Replace, AddedEffectAction.Update, AddedEffectAction.AddAgain)
            .firstOrNull { it in actions } ?: AddedEffectAction.Add
        val cancel = { if (!importer.busy) importer.dismissPreview() }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(stringResource(R.string.added_effect_question), color = Am.Navy) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(16.dp)) {
                    previews.forEach { preview ->
                        AddedEffectPreviewRow(preview, catalog.categories.toMap(), catalog.find(preview.effect.definition.id), actions.size > 1)
                    }
                    error?.let { Text(it.text(), color = Am.HomeMid) }
                }
            },
            confirmButton = {
                DialogPill(stringResource(action.label), enabled = working.isNotEmpty() && !importer.busy) { scope.launch { importer.install() } }
            },
            dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false, enabled = !importer.busy, onClick = cancel) },
            containerColor = Am.Card,
        )
    } else if (showFiles && error != null) {
        AlertDialog(
            onDismissRequest = importer::dismissError,
            text = { Text(error.text(), color = Am.Navy) },
            confirmButton = { DialogPill(stringResource(R.string.done), onClick = importer::dismissError) },
            containerColor = Am.Card,
        )
    } else {
        importer.projectPrompt?.let { ProjectEffectsDialog(it, importer::answer, pick) }
    }
    importer.removing?.let { effect ->
        val cancel = { importer.removing = null }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(stringResource(R.string.added_effect_remove_question, effect.name), color = Am.Navy) },
            confirmButton = {
                DialogPill(stringResource(R.string.added_effect_remove)) {
                    cancel()
                    scope.launch { importer.remove(effect) }
                }
            },
            dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false, onClick = cancel) },
            containerColor = Am.Card,
        )
    }
}

private val AddedEffectAction.label: Int get() = when (this) {
    AddedEffectAction.Add -> R.string.added_effect_add
    AddedEffectAction.Update -> R.string.added_effect_update
    AddedEffectAction.AddAgain -> R.string.added_effect_again
    AddedEffectAction.Replace -> R.string.added_effect_replace
}

private fun EffectCatalog.action(effect: AddedEffect) =
    addedEffectAction(effect.definition.addedVersion, find(effect.definition.id)?.addedVersion, sameAddedAuthor(effect.definition))

@Composable
private fun ProjectEffectsDialog(prompt: ProjectEffectPrompt, answer: (ProjectEffectAnswer) -> Unit, pick: () -> Unit) {
    val open = { answer(ProjectEffectAnswer.Open) }
    val add = { answer(ProjectEffectAnswer.Add) }
    val next = { answer(ProjectEffectAnswer.Continue) }
    when (prompt) {
        is ProjectEffectPrompt.Bundle -> AlertDialog(
            onDismissRequest = open,
            text = {
                Text(stringResource(R.string.added_effect_bundle_question, prompt.names.joinToString(", ")), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), color = Am.Navy)
            },
            confirmButton = { EffectDialogButton(R.string.community_add, onClick = add) },
            dismissButton = { EffectDialogButton(R.string.added_effect_open_without, primary = false, onClick = open) },
            containerColor = Am.Card,
        )
        is ProjectEffectPrompt.Missing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.added_effect_missing), color = Am.Navy) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                    val (updates, missing) = prompt.effects.partition { it.id in prompt.updates }
                    if (missing.isNotEmpty()) {
                        Text(stringResource(R.string.added_effect_missing_body, missing.joinToString(", ") { it.name }), color = Am.HomeMid)
                    }
                    if (updates.isNotEmpty()) {
                        Text(stringResource(R.string.added_effect_project_needs_update, updates.joinToString(", ") { it.name }), color = Am.HomeMid)
                    }
                }
            },
            confirmButton = { EffectDialogButton(if (prompt.updates.isNotEmpty()) R.string.added_effect_update else R.string.added_effect_from_file, onClick = pick) },
            dismissButton = {
                if (prompt.look) EffectDialogButton(R.string.added_effect_community_look, primary = false) { answer(ProjectEffectAnswer.Look) }
                EffectDialogButton(R.string.added_effect_open_anyway, primary = false, onClick = open)
            },
            containerColor = Am.Card,
        )
        ProjectEffectPrompt.Consent -> AlertDialog(
            onDismissRequest = next,
            title = { Text(stringResource(R.string.community_ask_title), color = Am.Navy) },
            text = { Text(stringResource(R.string.community_ask_note), color = Am.HomeMid) },
            confirmButton = { EffectDialogButton(R.string.community_ask_button, onClick = add) },
            dismissButton = { EffectDialogButton(R.string.cancel, primary = false, onClick = next) },
            containerColor = Am.Card,
        )
        is ProjectEffectPrompt.Packs -> AlertDialog(
            onDismissRequest = open,
            title = { Text(stringResource(R.string.added_effect_community_question), color = Am.Navy) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(12.dp)) {
                    for ((pack, missing) in prompt.packs) {
                        val creator = pack.creator.ifBlank { stringResource(R.string.added_effect_other_creators) }
                        Column {
                            Text(stringResource(R.string.added_effect_community_pack, pack.title.ifBlank { pack.name }, creator, formatBytes(pack.bytes)), color = Am.Navy)
                            Text(missing.joinToString(", ") { it.name }, color = Am.HomeMid)
                        }
                    }
                }
            },
            confirmButton = { EffectDialogButton(if (prompt.update) R.string.added_effect_update else R.string.update_install, onClick = add) },
            dismissButton = { EffectDialogButton(R.string.added_effect_open_without, primary = false, onClick = open) },
            containerColor = Am.Card,
        )
        is ProjectEffectPrompt.Progress -> AlertDialog(
            onDismissRequest = { if (prompt.cancellable) open() },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Am.Navy)
                    Text(prompt.label.text(), color = Am.Navy)
                }
            },
            confirmButton = { if (prompt.cancellable) EffectDialogButton(R.string.cancel, primary = false, onClick = open) },
            containerColor = Am.Card,
        )
        is ProjectEffectPrompt.Notice -> AlertDialog(
            onDismissRequest = if (prompt.canOpen) open else next,
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                    prompt.messages.forEach { Text(it.text(), color = Am.Navy) }
                }
            },
            confirmButton = {
                if (prompt.retry) EffectDialogButton(R.string.aibrowser_retry) { answer(ProjectEffectAnswer.Retry) }
                else EffectDialogButton(R.string.done, onClick = next)
            },
            dismissButton = {
                if (prompt.canOpen) EffectDialogButton(R.string.added_effect_open_without, primary = false, onClick = open)
            },
            containerColor = Am.Card,
        )
    }
}

@Composable
private fun EffectDialogButton(label: Int, primary: Boolean = true, onClick: () -> Unit) = DialogPill(stringResource(label), primary = primary, onClick = onClick)

@Composable
private fun AddedEffectPreviewRow(preview: AddedEffectPreview, categories: Map<String, String>, installedEffect: EffectDefinition?, mixed: Boolean) {
    val installed = installedEffect?.addedVersion
    val otherAuthor = installedEffect?.addedAuthor?.trim()?.takeUnless { it.equals(preview.effect.definition.addedAuthor.trim(), ignoreCase = true) }
    val effect = preview.effect
    val definition = effect.definition
    val picture = remember(preview.picture) { decodeAddedEffectPicture(preview.picture) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        picture?.let { Image(it, null, Modifier.size(92.dp), contentScale = ContentScale.Fit) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(definition.name, color = Am.Navy)
            if (effect.author.isNotBlank()) Text(stringResource(R.string.added_effect_author, effect.author), color = Am.HomeMid)
            Text(stringResource(R.string.added_effect_version, definition.addedVersion), color = Am.HomeMid)
            definition.category?.let(categories::get)?.let { Text(it, color = Am.HomeMid) }
            Text(stringResource(R.string.added_effect_settings, definition.params.size), color = Am.HomeMid)
            if (effect.licence.isNotBlank()) Text(effect.licence, color = Am.HomeMid)
            if (definition.description.isNotBlank()) Text(definition.description, color = Am.HomeMid)
            Text(stringResource(if (preview.works) R.string.added_effect_works else R.string.added_effect_does_not_work), color = Am.Navy)
            when (addedEffectAction(definition.addedVersion, installed, otherAuthor == null)) {
                AddedEffectAction.AddAgain -> Text(stringResource(R.string.added_effect_already_added), color = Am.HomeMid)
                AddedEffectAction.Replace -> Text(
                    if (otherAuthor != null) stringResource(R.string.added_effect_replaces_other, otherAuthor.ifBlank { stringResource(R.string.added_effect_other_creators) })
                    else stringResource(R.string.added_effect_newer_installed, checkNotNull(installed)),
                    color = Am.HomeMid,
                )
                AddedEffectAction.Update -> if (mixed) Text(stringResource(R.string.added_effect_update), color = Am.HomeMid)
                AddedEffectAction.Add -> Unit
            }
        }
    }
}

internal fun decodeAddedEffectPicture(bytes: ByteArray?): ImageBitmap? = bytes?.let {
    BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
}
