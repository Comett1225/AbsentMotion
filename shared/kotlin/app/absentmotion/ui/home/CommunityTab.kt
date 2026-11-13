package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.library.CommunityClient
import app.absentmotion.library.CommunityDamagedException
import app.absentmotion.library.CommunityIndex
import app.absentmotion.library.CommunityItem
import app.absentmotion.library.CommunityKind
import app.absentmotion.library.CommunitySource
import app.absentmotion.library.PresetStore
import app.absentmotion.library.addCommunityPreset
import app.absentmotion.media.pictureSample
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.Toast
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private var sessionIndex: CommunityIndex? = null

private enum class CommunityFilter {
    All,
    Projects,
    Presets
}

private class ActiveDownload(
    val http: TrustedHttp,
    val job: Job,
    val cancelled: AtomicBoolean
)

@Composable
fun CommunityTab(modifier: Modifier, onImport: (File) -> Unit) {
    val context = LocalContext.current
    val isDesktop = PlatformFeatures.desktop
    val client = remember(context) { CommunityClient(context.cacheDir) { TrustedHttp(CommunitySource) } }
    var index by remember { mutableStateOf(sessionIndex) }
    var loading by remember { mutableStateOf(sessionIndex == null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(CommunityFilter.All) }
    var selected by remember { mutableStateOf<CommunityItem?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap?>() }

    LaunchedEffect(attempt) {
        val cached = sessionIndex
        if (cached != null) {
            index = cached
            loading = false
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val fetched = withContext(Dispatchers.IO) {
            try {
                client.index()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (fetched != null) {
            sessionIndex = fetched
            index = fetched
            failed = false
        } else {
            failed = true
        }
        loading = false
    }

    val current = index
    when {
        loading -> Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Am.Navy)
        }

        failed || current == null -> CommunityErrorState(modifier) {
            sessionIndex = null
            failed = false
            loading = true
            attempt++
        }

        current.tooNew -> CommunityMessageState(
            modifier = modifier,
            title = stringResource(R.string.community_too_new)
        )

        else -> {
            val pad = if (isDesktop) 24.dp else 16.dp
            val filtered = remember(current.items, filter) {
                when (filter) {
                    CommunityFilter.All -> current.items
                    CommunityFilter.Projects -> current.items.filter { it.kind == CommunityKind.Bundle }
                    CommunityFilter.Presets -> current.items.filter { it.kind == CommunityKind.Preset }
                }
            }
            Column(modifier.fillMaxSize()) {
                Spacer(Modifier.height(if (isDesktop) 16.dp else 8.dp))
                CommunityFilterBar(
                    filter = filter,
                    onSelect = { filter = it },
                    modifier = Modifier.padding(horizontal = pad)
                )
                Spacer(Modifier.height(4.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), Alignment.Center) {
                        Text(
                            text = stringResource(R.string.community_empty),
                            color = Am.HomeMid,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = pad, end = pad, top = 12.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filtered, key = { "${it.kind.id}/${it.name}" }) { item ->
                            val thumbPath = item.thumbnail
                            if (thumbPath != null) {
                                LaunchedEffect(thumbPath) {
                                    if (!thumbs.containsKey(thumbPath)) {
                                        val bmp = withContext(Dispatchers.IO) {
                                            decodeCommunityThumb(client.thumbnail(item))
                                        }
                                        thumbs[thumbPath] = bmp
                                    }
                                }
                            }
                            CommunityGridCard(
                                item = item,
                                thumb = thumbPath?.let { thumbs[it] },
                                onClick = { selected = item }
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { item ->
        CommunityItemDialog(
            item = item,
            thumb = item.thumbnail?.let { thumbs[it] },
            onDismiss = { selected = null },
            onImport = onImport
        )
    }
}

@Composable
private fun CommunityErrorState(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        Icon(HomeIcons.CommunityTab, null, Modifier.size(40.dp), tint = Am.HomeMid)
        Text(
            text = stringResource(R.string.community_error_title),
            modifier = Modifier.padding(top = 14.dp),
            color = Am.Navy,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.community_error_note),
            modifier = Modifier.padding(top = 8.dp),
            color = Am.HomeMid,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Am.Navy)
                .deskPress(8.dp)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.community_try_again),
                color = Am.OnNavy,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CommunityMessageState(modifier: Modifier, title: String) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        Icon(HomeIcons.CommunityTab, null, Modifier.size(40.dp), tint = Am.HomeMid)
        Text(
            text = title,
            modifier = Modifier.padding(top = 14.dp),
            color = Am.Navy,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CommunityFilterBar(
    filter: CommunityFilter,
    onSelect: (CommunityFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .background(Am.Card, RoundedCornerShape(8.dp))
            .border(1.dp, Am.HomeLine, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        CommunitySegment(
            label = stringResource(R.string.community_filter_all),
            selected = filter == CommunityFilter.All,
            onClick = { onSelect(CommunityFilter.All) }
        )
        CommunitySegment(
            label = stringResource(R.string.community_filter_projects),
            selected = filter == CommunityFilter.Projects,
            onClick = { onSelect(CommunityFilter.Projects) }
        )
        CommunitySegment(
            label = stringResource(R.string.community_filter_presets),
            selected = filter == CommunityFilter.Presets,
            onClick = { onSelect(CommunityFilter.Presets) }
        )
    }
}

@Composable
private fun CommunitySegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Am.Navy else Color.Transparent)
            .deskPress(6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Am.OnNavy else Am.HomeMid,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun CommunityGridCard(
    item: CommunityItem,
    thumb: ImageBitmap?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Am.Card)
            .border(1.dp, Am.HomeLine, shape)
            .deskPress(12.dp)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Am.HomePanel),
            Alignment.Center
        ) {
            if (thumb != null) {
                Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(HomeIcons.CommunityTab, null, Modifier.size(36.dp), tint = Am.HomeMid)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title.ifEmpty { item.name },
            color = Am.Navy,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (item.creator.isNotEmpty()) {
            Text(
                text = stringResource(R.string.community_by, item.creator),
                modifier = Modifier.padding(top = 2.dp),
                color = Am.HomeMid,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        val kindText = stringResource(
            if (item.kind == CommunityKind.Bundle) R.string.community_kind_project
            else R.string.community_kind_preset
        )
        Text(
            text = kindText,
            modifier = Modifier
                .background(Am.HomePanel, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = Am.HomeDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun CommunityItemDialog(
    item: CommunityItem,
    thumb: ImageBitmap?,
    onDismiss: () -> Unit,
    onImport: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeDownload by remember(item) { mutableStateOf<ActiveDownload?>(null) }
    val downloading = activeDownload != null
    val addLabel = stringResource(
        if (item.kind == CommunityKind.Bundle) R.string.community_add
        else R.string.community_add_preset
    )

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (thumb != null && thumb.height > 0) {
                                (thumb.width.toFloat() / thumb.height).coerceIn(0.75f, 1.8f)
                            } else {
                                1.5f
                            }
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(Am.HomePanel),
                    Alignment.Center
                ) {
                    if (thumb != null) {
                        Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(HomeIcons.CommunityTab, null, Modifier.size(44.dp), tint = Am.HomeMid)
                    }
                }
                Text(
                    text = item.title.ifEmpty { item.name },
                    modifier = Modifier.padding(top = 4.dp),
                    color = Am.Navy,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                if (item.creator.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.community_by, item.creator),
                        color = Am.Navy.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
                if (item.description.isNotEmpty()) {
                    Text(
                        text = item.description,
                        color = Am.Navy,
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                }
                if (item.tags.isNotEmpty()) {
                    Text(
                        text = item.tags.joinToString("  \u00B7  "),
                        color = Am.Navy.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = app.absentmotion.export.formatBytes(item.bytes),
                    color = Am.HomeMid,
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val active = activeDownload
                    if (active != null) {
                        active.cancelled.set(true)
                        active.http.abort()
                        active.job.cancel()
                        activeDownload = null
                    }
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.cancel), color = Am.Navy)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (activeDownload != null) return@TextButton
                    val http = TrustedHttp(CommunitySource)
                    val cancelled = AtomicBoolean(false)
                    val job = scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                val downloaded = CommunityClient(context.cacheDir) { http }.download(item)
                                if (item.kind == CommunityKind.Preset) {
                                    addCommunityPreset(downloaded, PresetStore(context))
                                }
                                downloaded
                            }
                            activeDownload = null
                            onDismiss()
                            if (item.kind == CommunityKind.Bundle) {
                                onImport(file)
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.community_added_preset),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: CancellationException) {
                            activeDownload = null
                            throw e
                        } catch (_: CommunityDamagedException) {
                            activeDownload = null
                            if (!cancelled.get()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.community_download_damaged),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (_: Exception) {
                            activeDownload = null
                            if (!cancelled.get()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.community_download_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    activeDownload = ActiveDownload(http, job, cancelled)
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (downloading) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Am.Navy, strokeWidth = 2.dp)
                    }
                    Text(
                        text = addLabel,
                        color = if (downloading) Am.HomeDim else Am.Navy,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        containerColor = Am.Card
    )
}

private fun decodeCommunityThumb(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampled = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = pictureSample(maxOf(bounds.outWidth, bounds.outHeight), 512)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
