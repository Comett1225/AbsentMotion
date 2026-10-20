package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay

@Composable
fun OpeningOverlay() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); shown = true }
    Box(
        Modifier.fillMaxSize().background(if (shown) Color.Black.copy(alpha = 0.35f) else Color.Transparent).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        if (shown) CircularProgressIndicator(Modifier.size(44.dp), color = Am.Accent)
    }
}

@Composable
fun OpenFailedDialog(onHistory: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Could not open this project", color = Am.Navy) },
        text = {
            Text(
                "The project file is missing or damaged, so nothing was changed." +
                    if (onHistory != null) " You can bring back an earlier version from Version history." else "",
                color = Am.Navy,
            )
        },
        dismissButton = onHistory?.let { { TextButton(it) { Text("Version history", color = Am.Navy) } } },
        confirmButton = { TextButton(onDismiss) { Text("OK", color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
