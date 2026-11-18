package app.absentmotion.ui.adaptive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.platform.PlatformFeatures

object DeskLayout {
    var detected by mutableStateOf(false)

    val on: Boolean get() = PlatformFeatures.desktop || detected
}

val deskLayout: Boolean get() = DeskLayout.on
