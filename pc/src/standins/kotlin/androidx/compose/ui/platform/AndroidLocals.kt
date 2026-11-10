package androidx.compose.ui.platform

import android.content.Context
import android.content.Desk
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { Desk.context }
val LocalConfiguration = androidx.compose.runtime.compositionLocalOf { Configuration() }

val LocalView = staticCompositionLocalOf<android.view.View> { android.view.View.Desktop }
