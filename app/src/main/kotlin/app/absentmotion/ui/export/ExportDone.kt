package app.absentmotion.ui.export
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportResult
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.presence

@Composable
fun DoneScreen(kind: ExportKind, result: ExportResult, onClose: () -> Unit, onShare: () -> Unit) {
    app.absentmotion.platform.ExportBars(dark = true, running = false)
    Column(Modifier.fillMaxSize().presence().enterFade(10.dp).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Saved to storage", color = Am.OnNavy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(result.where, color = Am.OnNavy.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Am.Scrim)) {
            app.absentmotion.platform.ExportMediaPreview(kind, result.uris)
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            BottomButton(shareLabel(kind), ExportIcons.Share, onShare)
            BottomButton("OK", null, onClose)
        }
    }
}

private fun shareLabel(kind: ExportKind) = when (kind) {
    ExportKind.Video -> "Share Video"
    ExportKind.Gif -> "Share GIF"
    ExportKind.Sequence -> "Share Image Sequence"
    ExportKind.Frame, ExportKind.FrameAs -> "Share Image"
    ExportKind.Xml -> "Share Project XML"
    ExportKind.Package -> "Share"
}

@Composable
private fun BottomButton(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(57.dp).clip(RoundedCornerShape(5.dp)).background(Am.NavyHi).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(horizontal = 15.dp).size(20.dp), tint = Am.OnNavy)
        Text(label, Modifier.padding(end = if (icon != null) 15.dp else 0.dp), color = Am.OnNavy, fontSize = 17.sp)
    }
}

