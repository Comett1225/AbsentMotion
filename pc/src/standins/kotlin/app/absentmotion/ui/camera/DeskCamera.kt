package app.absentmotion.ui.camera

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun CameraScreen(aspect: Float, onClose: () -> Unit, onCaptured: (Uri, Boolean) -> Unit) {
    LaunchedEffect(Unit) { onClose() }
}

internal fun cameraRatio(aspect: Float, portrait: Boolean): Int = 0

internal fun cameraBlocked(granted: Boolean, rationale: Boolean, refusedBefore: Boolean): Boolean = !granted && !rationale && refusedBefore
