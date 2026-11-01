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

val panel = Color(0xff25272d)
val preview = Color(0xff14161a)
val timeline = Color(0xff1c1e24)
val accent = Color(0xffff8f66)
val muted = Color(0xffa9abb2)

@Composable
fun DividerDrag(vertical: Boolean, change: (Float) -> Unit) {
    Box(Modifier.then(if (vertical) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth()).background(Color(0xff383b42)).pointerInput(vertical) { detectDragGestures { _, drag -> change(if (vertical) drag.x else drag.y) } })
}

@Composable
fun SectionTitle(title: String) = Text(title, Modifier.fillMaxWidth().background(Color(0xff30333b)).padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp, color = Color.White)

fun formatTime(ms: Int, fps: Int): String = "%02d:%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, (ms % 1000) * fps / 1000)
