package app.absentmotion.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object PyEngine {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("ampy") }.isSuccess }
    @Volatile private var handler: ((JSONObject) -> String)? = null
    private var started = false

    @JvmStatic private external fun nativeStart(libs: Array<String>, home: String): String?
    @JvmStatic private external fun nativeRun(code: String): Int

    @Synchronized
    fun start(libs: List<String>, home: String, boot: String) {
        if (started) return
        check(loaded) { "the downloader library is missing" }
        nativeStart(libs.toTypedArray(), home)?.let { error(it) }
        check(nativeRun(boot) == 0) { "the downloader did not start" }
        started = true
    }

    @Synchronized
    fun run(code: String, handle: (JSONObject) -> String): Int {
        handler = handle
        try {
            return nativeRun(code)
        } finally {
            handler = null
        }
    }

    @Keep
    @JvmStatic
    fun call(request: ByteArray): ByteArray {
        val message = runCatching { JSONObject(String(request, Charsets.UTF_8)) }.getOrNull() ?: return ByteArray(0)
        val answer = try {
            handler?.invoke(message).orEmpty()
        } catch (e: Exception) {
            Log.w("AMDownloader", "callback failed", e)
            ""
        }
        return answer.toByteArray(Charsets.UTF_8)
    }
}

internal class WebViewJs(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var done = CountDownLatch(1)

    @SuppressLint("SetJavaScriptEnabled")
    private fun open() {
        if (view != null) return
        ready = CountDownLatch(1)
        view = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { ready.countDown() }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (this@WebViewJs.view === view) this@WebViewJs.view = null
                    view?.destroy()
                    ready.countDown()
                    done.countDown()
                    return true
                }
            }
            loadUrl("about:blank")
        }
    }

    fun run(code: String, timeoutSeconds: Long = 90): String {
        val opened = CountDownLatch(1)
        main.post { runCatching { open() }; opened.countDown() }
        check(opened.await(15, TimeUnit.SECONDS) && ready.await(15, TimeUnit.SECONDS)) { "the JavaScript engine did not start" }
        val finished = CountDownLatch(1).also { done = it }
        var answer: String? = null
        main.post {
            val target = view
            if (target == null) finished.countDown() else target.evaluateJavascript(wrap(code)) { value -> answer = value; finished.countDown() }
        }
        check(finished.await(timeoutSeconds, TimeUnit.SECONDS)) { "the JavaScript engine took too long" }
        val decoded = answer?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
        return decoded as? String ?: error("the JavaScript engine gave no answer")
    }

    override fun close() {
        main.post {
            view?.destroy()
            view = null
        }
    }

    private fun wrap(code: String) = "(function(){var out=null;var console={log:function(s){out=String(s);},error:function(){},warn:function(){},info:function(){},debug:function(){}};" +
        "try{\n$code\n}catch(e){return JSON.stringify({type:'error',error:String(e&&e.stack||e)});}" +
        "if(out){try{var o=JSON.parse(out);delete o.preprocessed_player;out=JSON.stringify(o);}catch(e){}}return out;})()"
}
