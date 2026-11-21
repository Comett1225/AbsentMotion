package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Suppress("DEPRECATION")
@Composable
internal fun ProjectLinkDialog(onDismiss: () -> Unit, initialValue: String = "", onImport: suspend (Uri, Boolean) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var failure by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf<TrustedHttp?>(null) }
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

    val edit: (String) -> Unit = { value = it.take(8193); failure = null }
    val shape = RoundedCornerShape(16.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Am.HomeBg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(HomeIcons.Link, null, Modifier.size(20.dp), tint = Am.Navy)
                }
                Text(stringResource(R.string.project_link_import), color = Am.Navy, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value, edit, Modifier.fillMaxWidth(), shape = shape,
                    label = { Text(stringResource(R.string.project_link_url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = TextStyle(fontSize = 15.sp, textDirection = TextDirection.Ltr).withUiFont(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = active == null,
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
                if (active != null || lookingUp) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Am.Navy, trackColor = Am.HomeBg)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ clipboard.getText()?.text?.let(edit) }, enabled = active == null) {
                    Text(stringResource(R.string.downloader_paste), color = if (active == null) Am.Navy else Am.HomeMid)
                }
                TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Am.Navy) }
            }
        },
        confirmButton = {
            Button(
                enabled = link != null && active == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Am.Navy, contentColor = Am.OnNavy,
                    disabledContainerColor = Am.HomeBg, disabledContentColor = Am.HomeMid,
                ),
                onClick = {
                    if (link == null || active != null) return@Button
                    val http = projectFileHttp(link)
                    active = http
                    failure = null
                    scope.launch {
                        try {
                            withProjectLinkFile(link, context.cacheDir, http) { onImport(Uri.fromFile(it), isAlightShare(link)) }
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ImportFailed) {
                            failure = e.text.resolve(context)
                        } catch (_: MobileDataRefused) {
                            failure = context.getString(R.string.mobile_data_refused)
                        } catch (_: Exception) {
                            failure = context.getString(R.string.community_download_failed)
                        } finally {
                            active = null
                        }
                    }
                },
            ) { Text(stringResource(R.string.desk_import)) }
        },
        containerColor = Am.Card,
    )
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
