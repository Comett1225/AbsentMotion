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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.i18n.AppLanguage
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.theme.Am
import java.io.ByteArrayInputStream

private const val HELP_ROOT = "file:///android_asset/help/"
private const val HELP_THUMBS = "file:///android_asset/effects/thumb/"
private val HELP_LANGUAGES = AppLanguage.entries.filter { it != AppLanguage.English }.map { it.tag }.toSet()
private val SAFE_PAGE = Regex("[A-Za-z0-9_./#-]+")

internal fun helpUrl(tag: String, page: String, exists: (String) -> Boolean): String {
    val safe = if (SAFE_PAGE.matches(page) && !page.startsWith("/") && !page.contains("..")) page else "index.html"
    if (tag in HELP_LANGUAGES && exists("help/$tag/${safe.substringBefore('#')}")) return "$HELP_ROOT$tag/$safe"
    return HELP_ROOT + safe
}

internal fun helpPage(url: String?): String? {
    val rest = url?.takeIf { it.startsWith(HELP_ROOT) }?.removePrefix(HELP_ROOT) ?: return null
    return if ('/' in rest && rest.substringBefore('/') in HELP_LANGUAGES) rest.substringAfter('/') else rest
}

internal fun helpAllowed(url: String) = url.startsWith(HELP_ROOT) && !url.contains("..") && !url.contains("%2e", ignoreCase = true)

@Composable
fun HelpScreen(page: String = "index.html", onClose: () -> Unit) {
    val links = LocalUriHandler.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tag = remember(configuration) { currentLanguage(context).tag }
    val exists = remember(context) { { path: String -> runCatching { context.assets.open(path).close() }.isSuccess } }
    val client = remember { HelpClient(tag) { url -> runCatching { links.openUri(url) } } }
    var web by remember { mutableStateOf<WebView?>(null) }
    val dark = Am.Card.luminance() < 0.5f
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    LaunchedEffect(tag, web) {
        val view = web ?: return@LaunchedEffect
        if (client.tag == tag) return@LaunchedEffect
        client.tag = tag
        client.clearHistory = true
        view.loadUrl(helpUrl(tag, helpPage(view.url) ?: page, exists))
    }
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
                            webViewClient = client
                            loadUrl(helpUrl(client.tag, page, exists))
                            web = this
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                )
            }
        }
    }
}

private class HelpClient(var tag: String, private val openExternal: (String) -> Unit) : WebViewClient() {
    var clearHistory = false

    override fun onPageFinished(view: WebView, url: String) {
        if (!clearHistory) return
        clearHistory = false
        view.clearHistory()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (helpAllowed(url)) return false
        if (request.url.scheme == "https") openExternal(url)
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (helpAllowed(url) || url.startsWith(HELP_THUMBS) && !url.contains("..")) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
