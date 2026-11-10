package app.absentmotion.ui.editor.effects

import app.absentmotion.ui.editor.deskSp
import androidx.compose.foundation.Canvas
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.editor.ChromeIcons
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale

private val Palette = listOf(
    Color(0xFF3F6FB5) to Color(0xFF48A3B8), Color(0xFF7862A8) to Color(0xFF467B8B), Color(0xFFB5657A) to Color(0xFF8B5FA8),
    Color(0xFF4F9A6A) to Color(0xFF3F7FA8), Color(0xFFB58A3F) to Color(0xFFB5603F), Color(0xFF5F6FB5) to Color(0xFF8F5FB5),
)

@Composable
internal fun AudioBackdrop(modifier: Modifier, seed: Int = 0) {
    val (from, to) = Palette[seed.mod(Palette.size)]
    Canvas(modifier.background(Brush.linearGradient(listOf(from, to)))) {
        val cx = size.width / 2
        val cy = size.height / 2
        val unit = minOf(size.width, size.height)
        for (i in -4..4) {
            val h = unit * (0.05f + 0.2f * (((i + 5) * 37 + seed * 53 + (seed * i) * 11).mod(9)) / 8f)
            val x = cx + i * unit * 0.06f
            drawLine(Color.White.copy(alpha = 0.85f), Offset(x, cy - h), Offset(x, cy + h), unit * 0.022f, StrokeCap.Round)
        }
    }
}

@Composable
internal fun AudioTile(label: String, seed: Int, enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(Modifier.width(100.dp).pressScale(press, TilePress).alpha(if (enabled) 1f else 0.35f)
        .clickable(press, LocalIndication.current, enabled = enabled, onClick = onClick).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AudioBackdrop(Modifier.size(93.dp).clip(RoundedCornerShape(5.dp)), seed)
        Text(label, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDark, fontSize = deskSp(10f), lineHeight = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun AudioFolderTile(label: String, count: Int, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth().padding(5.dp).height(66.5.dp).pressScale(press, TilePress).clip(RoundedCornerShape(5.dp))
        .clickable(press, LocalIndication.current, onClick = onClick)) {
        WaveRow(Modifier.fillMaxSize(), 2)
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HomeIcons.Folder, null, Modifier.size(26.dp), tint = Color.White)
            val shade = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 10f))
            Text(label, Modifier.weight(1f).padding(start = 10.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, style = shade)
            Text(count.toString(), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, style = shade)
            Icon(ChromeIcons.ChevronRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun WaveRow(modifier: Modifier, seed: Int) {
    val (from, to) = Palette[seed.mod(Palette.size)]
    Canvas(modifier.background(Brush.linearGradient(listOf(from, to)))) {
        drawRect(Color.Black.copy(alpha = 0.55f))
        val unit = size.height
        val cy = size.height / 2
        val step = unit * 0.95f
        val reach = (size.width / 2 / step).toInt() + 1
        for (k in -reach..reach) {
            val cx = size.width / 2 + k * step
            val alpha = 0.1f + 0.32f * kotlin.math.abs(k) / reach
            val wave = seed + k * 7
            for (i in -4..4) {
                val h = unit * (0.05f + 0.2f * (((i + 5) * 37 + wave * 53 + (wave * i) * 11).mod(9)) / 8f)
                val x = cx + i * unit * 0.06f
                drawLine(Color.White.copy(alpha = alpha), Offset(x, cy - h), Offset(x, cy + h), unit * 0.022f, StrokeCap.Round)
            }
        }
    }
}
