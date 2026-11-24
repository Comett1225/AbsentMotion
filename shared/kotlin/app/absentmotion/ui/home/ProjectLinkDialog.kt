package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.library.AlightLinkPreview
import app.absentmotion.library.ImportFailed
import app.absentmotion.library.ProjectLinkImportFailure
import app.absentmotion.library.alightHttps
import app.absentmotion.library.alightLinkPreview
import app.absentmotion.library.alightPage
import app.absentmotion.library.alightPreviewHttp
import app.absentmotion.library.isAlightShare
import app.absentmotion.library.projectFileHttp
import app.absentmotion.library.projectFileLink
import app.absentmotion.library.withProjectLinkFile
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.MobileDataRefused
import app.absentmotion.platform.Uri
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.withUiFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.URI

@Suppress("DEPRECATION")
@Composable
internal fun ProjectLinkDialog(onDismiss: () -> Unit, initialValue: String = "", onImport: suspend (Uri, Boolean) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var failure by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf<TrustedHttp?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf<URI?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var qrLinks by remember { mutableStateOf(emptyList<URI>()) }
    var ticked by remember { mutableStateOf(emptySet<URI>()) }
    var failures by remember { mutableStateOf(emptyList<Pair<URI, String>>()) }
    val busy = scanning || importJob != null
    val bodyScroll = rememberScrollState()
    LaunchedEffect(importJob, failures) {
        if (qrLinks.isNotEmpty() && (importJob != null || failures.isNotEmpty())) bodyScroll.scrollTo(0)
    }
    val link = remember(value) { projectFileLink(value) }
    val share = link?.takeIf { isAlightShare(it) && alightPage(alightHttps(it)) }
    var preview by remember(share) { mutableStateOf<AlightLinkPreview?>(null) }
    var picture by remember(share) { mutableStateOf<ImageBitmap?>(null) }
    var lookingUp by remember(share) { mutableStateOf(share != null) }
    val previewHttp = remember(share) { share?.let { alightPreviewHttp() } }
    LaunchedEffect(previewHttp) {
        if (share == null || previewHttp == null) return@LaunchedEffect
        try {
            delay(450)
            val result = runInterruptible(Dispatchers.IO) {
                val card = alightLinkPreview(share, previewHttp)
                card to decodeCommunityThumb(card?.thumbnail)
            }
            preview = result.first
            picture = result.second
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            lookingUp = false
        }
    }
    DisposableEffect(previewHttp) { onDispose { previewHttp?.abort() } }
    DisposableEffect(Unit) { onDispose { active?.abort() } }

    val qrPreviews = remember(qrLinks) { mutableStateMapOf<URI, Pair<AlightLinkPreview, ImageBitmap?>>() }
    val qrPreviewHttp = remember(qrLinks) { if (qrLinks.any(::isAlightShare)) alightPreviewHttp() else null }
    LaunchedEffect(qrPreviewHttp) {
        if (qrPreviewHttp == null) return@LaunchedEffect
        for (candidate in qrLinks.filter(::isAlightShare)) {
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    val card = alightLinkPreview(candidate, qrPreviewHttp) ?: return@runInterruptible null
                    card to decodeCommunityThumb(card.thumbnail)
                }
                if (result != null) qrPreviews[candidate] = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
    DisposableEffect(qrPreviewHttp) { onDispose { qrPreviewHttp?.abort() } }

    val edit: (String) -> Unit = {
        value = it.take(8193)
        failure = null
        qrLinks = emptyList()
        ticked = emptySet()
        failures = emptyList()
    }
    val scan: (Uri) -> Unit = { uri ->
        if (!scanning && importJob == null) {
            scanning = true
            failure = null
            scope.launch {
                val texts = try {
                    runInterruptible(Dispatchers.IO) { readProjectQrPicture(context, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                } finally {
                    scanning = false
                }
                val candidates = texts.mapNotNull(::projectFileLink).distinct()
                when (candidates.size) {
                    0 -> {
                        edit(value)
                        failure = context.getString(R.string.project_link_qr_none)
                    }
                    1 -> edit(texts.first())
                    else -> {
                        edit("")
                        qrLinks = candidates
                        ticked = candidates.toSet()
                    }
                }
            }
        }
    }
    val close: () -> Unit = {
        active?.abort()
        importJob?.cancel()
        onDismiss()
    }
    val chosen = if (qrLinks.isEmpty()) listOfNotNull(link) else qrLinks.filter { it in ticked }
    val shape = RoundedCornerShape(16.dp)
    AlertDialog(
        onDismissRequest = close,
        modifier = projectQrDropTarget(!busy, scan),
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Am.HomeBg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(HomeIcons.Link, null, Modifier.size(20.dp), tint = Am.Navy)
                }
                Text(stringResource(R.string.project_link_import), color = Am.Navy, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(bodyScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value, edit, Modifier.fillMaxWidth(), shape = shape,
                    label = { Text(stringResource(R.string.project_link_url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = TextStyle(fontSize = 15.sp, textDirection = TextDirection.Ltr).withUiFont(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = !busy,
                    isError = failure != null || value.isNotBlank() && link == null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Am.HomeInk, unfocusedTextColor = Am.HomeInk,
                        errorTextColor = Am.HomeInk, disabledTextColor = Am.HomeMid,
                        cursorColor = Am.Navy, errorCursorColor = Am.Navy,
                        focusedLabelColor = Am.Navy, unfocusedLabelColor = Am.HomeMid,
                        errorLabelColor = Am.HomeMid, disabledLabelColor = Am.HomeMid,
                        focusedBorderColor = Am.Navy, unfocusedBorderColor = Am.HomeMid.copy(alpha = 0.65f),
                        focusedContainerColor = Am.HomeBg, unfocusedContainerColor = Am.HomeBg,
                        disabledContainerColor = Am.HomeBg, errorContainerColor = Am.HomeBg,
                    ),
                )
                preview?.let { LinkPreviewCard(it, picture) }
                failure?.let { Text(it, color = Am.HomeInk) }
                running?.takeIf { qrLinks.isNotEmpty() }?.let {
                    Text(
                        stringResource(R.string.project_link_qr_importing, qrPreviews[it]?.first?.title ?: projectQrLabel(it)),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = Am.HomeInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy || lookingUp) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Am.Navy, trackColor = Am.HomeBg)
                }
                ProjectQrPictureButtons(!busy, scan, onDevice = { close(); DeviceSend.scan(context, null) })
                if (failures.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.project_link_qr_failed), color = Am.Navy, fontWeight = FontWeight.SemiBold)
                        for ((candidate, reason) in failures) {
                            Text(candidate.toString(), color = Am.HomeInk, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(reason, color = Am.HomeMid, fontSize = 12.sp)
                        }
                    }
                }
                if (qrLinks.isNotEmpty()) {
                    Text(stringResource(R.string.project_link_qr_found, qrLinks.size), color = Am.Navy, fontWeight = FontWeight.SemiBold)
                    for (candidate in qrLinks) {
                        val card = qrPreviews[candidate]
                        ProjectQrRow(card?.first?.title ?: projectQrLabel(candidate), card?.second, candidate in ticked, !busy) { checked ->
                            ticked = if (checked) ticked + candidate else ticked - candidate
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogPill(stringResource(R.string.downloader_paste), primary = false, enabled = !busy) { clipboard.getText()?.text?.let(edit) }
                DialogPill(stringResource(R.string.cancel), primary = false, onClick = close)
            }
        },
        confirmButton = {
            Button(
                enabled = chosen.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Am.Navy, contentColor = Am.OnNavy,
                    disabledContainerColor = Am.HomeBg, disabledContentColor = Am.HomeMid,
                ),
                onClick = {
                    if (chosen.isEmpty() || importJob != null || scanning) return@Button
                    val batch = qrLinks.isNotEmpty()
                    failure = null
                    failures = emptyList()
                    importJob = scope.launch {
                        val failed = mutableListOf<Pair<URI, String>>()
                        try {
                            for (candidate in chosen) {
                                val http = projectFileHttp(candidate)
                                running = candidate
                                active = http
                                try {
                                    withProjectLinkFile(candidate, context.cacheDir, http) {
                                        onImport(Uri.fromFile(it), isAlightShare(candidate))
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    val reason = when (e) {
                                        is ImportFailed -> e.text.resolve(context)
                                        is ProjectLinkImportFailure -> e.detail
                                        is MobileDataRefused -> context.getString(R.string.mobile_data_refused)
                                        else -> context.getString(R.string.community_download_failed)
                                    }
                                    failed += candidate to reason
                                } finally {
                                    http.abort()
                                    active = null
                                }
                            }
                            when {
                                failed.isEmpty() -> onDismiss()
                                batch -> {
                                    failures = failed
                                    ticked = failed.mapTo(linkedSetOf()) { it.first }
                                }
                                else -> failure = failed.single().second
                            }
                        } finally {
                            running = null
                            importJob = null
                        }
                    }
                },
            ) {
                Text(if (qrLinks.isEmpty()) stringResource(R.string.desk_import)
                    else stringResource(R.string.project_link_qr_import_count, chosen.size))
            }
        },
        containerColor = Am.Card,
    )
}

@Composable
internal fun ProjectQrPictureButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    DialogPill(stringResource(label), primary = false, enabled = enabled, onClick = onClick)
}

private fun projectQrLabel(link: URI): String =
    if (isAlightShare(link)) link.host.orEmpty()
    else link.path.orEmpty().substringAfterLast('/').ifBlank { link.host.orEmpty() }

@Composable
private fun ProjectQrRow(title: String, picture: ImageBitmap?, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked, null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Am.Navy, uncheckedColor = Am.HomeMid, checkmarkColor = Am.OnNavy),
        )
        if (picture != null) {
            Image(picture, null, Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
        }
        Text(title, Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LinkPreviewCard(preview: AlightLinkPreview, picture: ImageBitmap?) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().background(Am.HomeBg, shape).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
        if (picture != null) Image(picture, null, thumb, contentScale = ContentScale.Fit)
        else Box(thumb.background(Am.Card), contentAlignment = Alignment.Center) {
            Icon(HomeIcons.Link, null, Modifier.size(24.dp), tint = Am.HomeMid)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(preview.title, color = Am.HomeInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            preview.description?.let { Text(it, color = Am.HomeMid, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}
