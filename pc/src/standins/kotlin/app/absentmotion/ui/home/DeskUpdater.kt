package app.absentmotion.ui.home

import android.content.Context
import androidx.compose.runtime.Composable

internal object Updater {
    fun inApp(context: Context, source: UpdateSource): Boolean = false
    fun reveal(): Boolean = false
}

@Composable
internal fun UpdateDialog() {}
