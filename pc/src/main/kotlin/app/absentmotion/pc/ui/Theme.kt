package app.absentmotion.pc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import app.absentmotion.pc.platform.DesktopPlatform

val panel = Color(0xff25272d)
val preview = Color(0xff14161a)
val timeline = Color(0xff1c1e24)
val accent = Color(0xffff8f66)
val muted = Color(0xffa9abb2)

object Ui {
    val Window = Color(0xff0e0f12)
    val TopBar = Color(0xff1a1c21)
    val Panel = Color(0xff25272d)
    val PanelHeader = Color(0xff2a2d34)
    val Inset = Color(0xff1a1c21)
    val Field = Color(0xff353941)
    val FieldHi = Color(0xff40454f)
    val Chosen = Color(0xff4a505c)
    val Line = Color(0xff32353d)
    val Viewer = Color(0xff111215)
    val Text = Color(0xfff2f3f5)
    val TextDim = Color(0xffa6acb8)
    val TextFaint = Color(0xff858b97)
    val Inactive = Color(0xff5a5f6a)
    val Accent = Color(0xffff8f66)
    val AccentLit = Color(0xffffb454)
    val AccentDeep = Color(0xff5c3626)
    val OnAccent = Color(0xff17181b)
    val Cool = Color(0xff6db8ff)
    val Warning = Color(0xffff8a94)
    val ClipVideo = Color(0xff4a78c8)
    val ClipPicture = Color(0xff3f9c8a)
    val ClipText = Color(0xff8a6ad0)
    val ClipShape = Color(0xffd0874f)
    val ClipAudio = Color(0xff4fa860)
    val ClipOther = Color(0xff6c7280)
    private fun font(dir: String, file: String, weight: FontWeight) = runCatching { Font(DesktopPlatform.assets().resolve("fonts/$dir/$file"), weight) }.getOrNull()
    val Sans: FontFamily by lazy { listOfNotNull(font("Inter", "Inter-Regular.ttf", FontWeight.Normal), font("Inter", "Inter-Bold.ttf", FontWeight.Bold)).takeIf { it.isNotEmpty() }?.let { FontFamily(it) } ?: FontFamily.Default }
    val Mono: FontFamily by lazy { listOfNotNull(font("JetBrainsMono", "JetBrainsMono-Regular.ttf", FontWeight.Normal), font("JetBrainsMono", "JetBrainsMono-Bold.ttf", FontWeight.Bold)).takeIf { it.isNotEmpty() }?.let { FontFamily(it) } ?: FontFamily.Monospace }
}

@Composable
fun DividerDrag(vertical: Boolean, change: (Float) -> Unit) {
    Box(Modifier.then(if (vertical) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth()).background(Color(0xff383b42)).pointerInput(vertical) { detectDragGestures { _, drag -> change(if (vertical) drag.x else drag.y) } })
}

@Composable
fun SectionTitle(title: String) = Text(title, Modifier.fillMaxWidth().background(Color(0xff30333b)).padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp, color = Color.White)

fun formatTime(ms: Int, fps: Int): String = "%02d:%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, (ms % 1000) * fps / 1000)
