package app.absentmotion.ui.adaptive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.platform.PlatformFeatures

enum class LayoutChoice {
    Auto, On, Off;

    companion object {
        fun of(name: String?): LayoutChoice = entries.firstOrNull { it.name == name } ?: Auto
    }
}

object DeskLayout {
    var choice by mutableStateOf(LayoutChoice.Auto)
    var detected by mutableStateOf(false)

    fun resolve(desktop: Boolean, choice: LayoutChoice, detected: Boolean): Boolean = desktop || when (choice) {
        LayoutChoice.On -> true
        LayoutChoice.Off -> false
        LayoutChoice.Auto -> detected
    }

    val on: Boolean get() = resolve(PlatformFeatures.desktop, choice, detected)
}

val deskLayout: Boolean get() = DeskLayout.on
