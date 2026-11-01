package androidx.compose.ui.viewinterop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun <T> AndroidView(factory: (android.content.Context) -> T, modifier: Modifier = Modifier, update: (T) -> Unit = {}) = Box(modifier)

@Composable
fun <T> AndroidView(factory: (android.content.Context) -> T, modifier: Modifier = Modifier, onReset: ((T) -> Unit)?, onRelease: (T) -> Unit = {}, update: (T) -> Unit = {}) = Box(modifier)
