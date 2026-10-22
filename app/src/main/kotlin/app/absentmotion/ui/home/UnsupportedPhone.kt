package app.absentmotion.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.Tier
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.platform.appVersion
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PhoneFacts(val device: String, val android: String, val gpu: String?, val vulkan: String?)

@StringRes
internal fun engineProblemText(problem: String): Int = when {
    problem.startsWith("Vulkan 1.1") -> R.string.unsupported_old_vulkan
    problem.startsWith("vkCreateInstance") || problem.startsWith("vkEnumeratePhysicalDevices") -> R.string.unsupported_no_vulkan
    problem.startsWith("graphics queue") || problem.startsWith("required device extensions") || problem.startsWith("vkCreateDevice") -> R.string.unsupported_driver_features
    problem.startsWith("SkiaVMA") || problem.startsWith("Graphite") || problem.startsWith("Ganesh") -> R.string.unsupported_skia
    else -> R.string.unsupported_unknown
}

internal fun engineProblemDetails(version: String, facts: PhoneFacts, problem: String): String = listOf(
    "AbsentMotion $version (${if (Tier.compatible) "Compatible" else "Latest"})",
    "Device: ${facts.device}",
    "Android: ${facts.android}",
    "Graphics chip: ${facts.gpu ?: "unknown"}",
    "Vulkan: ${facts.vulkan ?: "none"}",
    "Problem: $problem",
).joinToString("\n")

private fun phoneFacts(context: Context): PhoneFacts {
    val vulkan = context.packageManager.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        ?.takeIf { it > 0 }?.let { "${it ushr 22}.${(it ushr 12) and 0x3ff}" }
    return PhoneFacts("${Build.MANUFACTURER} ${Build.MODEL}", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", glRenderer(), vulkan)
}

private fun glRenderer(): String? = runCatching {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    check(EGL14.eglInitialize(display, version, 0, version, 1))
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    val wanted = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
    check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0)
    val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
    try {
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        listOfNotNull(GLES20.glGetString(GLES20.GL_RENDERER), GLES20.glGetString(GLES20.GL_VERSION)).joinToString(", ").ifEmpty { null }
    } finally {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}.getOrNull()

@Composable
fun UnsupportedPhone(problem: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val version = appVersion()
    val facts by produceState<PhoneFacts?>(null) { value = withContext(Dispatchers.IO) { phoneFacts(context) } }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Am.HomeBg).pointerInput(Unit) {}, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Text(stringResource(R.string.unsupported_title), color = Am.Navy, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(engineProblemText(problem)), color = Am.HomeInk, fontSize = 16.sp, lineHeight = 23.sp)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.unsupported_still_usable), color = Am.HomeMid, fontSize = 15.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(22.dp))
            Fact(stringResource(R.string.unsupported_fact_android), facts?.android)
            Fact(stringResource(R.string.unsupported_fact_gpu), facts?.let { it.gpu ?: stringResource(R.string.unsupported_fact_unknown) })
            Fact(stringResource(R.string.unsupported_fact_vulkan), facts?.let { it.vulkan ?: stringResource(R.string.unsupported_fact_none) })
            Spacer(Modifier.height(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(stringResource(R.string.unsupported_requirements), filled = false) { runCatching { links.openUri(HOME_PAGE) } }
                Action(stringResource(R.string.unsupported_copy), filled = false) {
                    val shown = facts ?: return@Action
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AbsentMotion", engineProblemDetails(version, shown, problem)))
                    Toast.makeText(context, context.getString(R.string.unsupported_copied), Toast.LENGTH_SHORT).show()
                }
                Action(stringResource(R.string.action_close), filled = true, onClick = onClose)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.widthIn(min = 130.dp).padding(end = 12.dp), color = Am.HomeMid, fontSize = 14.sp)
        Text(value.orEmpty(), Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun Action(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(if (filled) Am.Navy else Am.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) Am.OnNavy else Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}
