package app.absentmotion.ui.editor

import androidx.compose.runtime.*
import app.absentmotion.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.absentmotion.audio.DeviceAudio
import app.absentmotion.audio.deviceAudio
import app.absentmotion.media.DeviceMedia
import app.absentmotion.media.deviceMedia
import app.absentmotion.media.mediaThumbnail
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class AddLibrary {
    var media by mutableStateOf(emptyList<DeviceMedia>())
    var audio by mutableStateOf(emptyList<DeviceAudio>())
    var mediaRevision by mutableIntStateOf(0)
    var audioRevision by mutableIntStateOf(0)
}

@Composable
internal fun rememberAddLibrary(demoMode: Boolean): AddLibrary {
    val context = LocalContext.current.applicationContext
    val library = remember { AddLibrary() }
    LaunchedEffect(library.mediaRevision, demoMode) {
        library.media = deviceMedia(context, demoMode)
        coroutineScope {
            library.media.take(10).chunked(4).forEach { batch ->
                coroutineScope { batch.forEach { file -> launch { mediaThumbnail(context, file.uri) } } }
            }
        }
    }
    LaunchedEffect(library.audioRevision) { library.audio = deviceAudio(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { library.mediaRevision++; library.audioRevision++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return library
}
