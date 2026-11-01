package app.absentmotion.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun HelpScreen(page: String = "index.html", onClose: () -> Unit) {
    LaunchedEffect(page) {
        val clean = page.substringBefore('#').takeIf { !it.startsWith("/") && !it.contains("..") } ?: "index.html"
        val file = android.content.AssetManager.root().resolve("help").resolve(clean).takeIf { it.isFile } ?: android.content.AssetManager.root().resolve("help/index.html")
        runCatching { java.awt.Desktop.getDesktop().browse(file.toURI()) }
        onClose()
    }
}
