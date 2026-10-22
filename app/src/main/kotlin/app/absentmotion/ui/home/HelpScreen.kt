package app.absentmotion.ui.home

import android.content.res.Configuration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.theme.Am
import java.io.ByteArrayInputStream

private const val HELP_ROOT = "file:///android_asset/help/"
private const val HELP_THUMBS = "file:///android_asset/effects/thumb/"

@Composable
fun HelpScreen(page: String = "index.html", onClose: () -> Unit) {
    val links = LocalUriHandler.current
    var web by remember { mutableStateOf<WebView?>(null) }
    val dark = Am.Card.luminance() < 0.5f
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    Dialog({ web?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = !dark)
            Column(Modifier.fillMaxSize().background(Am.SheetBg)) {
                TitleBar(stringResource(R.string.settings_help), HomeIcons.Close, stringResource(R.string.action_close), onClose)
                AndroidView(
                    factory = { base ->
                        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        val themed = Configuration(base.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
                        WebView(base.createConfigurationContext(themed)).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            settings.blockNetworkLoads = true
                            webViewClient = HelpClient { url -> runCatching { links.openUri(url) } }
                            loadUrl(HELP_ROOT + page)
                            web = this
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                )
            }
        }
    }
}

private class HelpClient(private val openExternal: (String) -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (url.startsWith(HELP_ROOT)) return false
        if (request.url.scheme == "https") openExternal(url)
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith(HELP_ROOT) || url.startsWith(HELP_THUMBS)) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
