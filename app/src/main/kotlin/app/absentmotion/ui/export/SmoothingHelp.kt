package app.absentmotion.ui.export

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.formatBytes
import app.absentmotion.ai.AiDevice
import app.absentmotion.ai.AiEstimate
import app.absentmotion.ai.AiSmooth
import app.absentmotion.ai.aiEstimate
import app.absentmotion.ai.model
import app.absentmotion.ui.onboarding.PhoneSpeed
import app.absentmotion.ui.onboarding.phoneCheckResult

@Composable
internal fun SmoothingHelp(color: Color, modifier: Modifier = Modifier) {
    val capabilities = DeviceCapabilities.of(LocalContext.current)
    val report by capabilities.report.collectAsState()
    LaunchedEffect(capabilities) { capabilities.load() }
    val speed = report?.let { phoneCheckResult(it, if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0).speed }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (speed != null) {
            val name = when (speed) {
                PhoneSpeed.Fast -> R.string.onboarding_speed_fast
                PhoneSpeed.Good -> R.string.onboarding_speed_good
                PhoneSpeed.Basic -> R.string.onboarding_speed_basic
            }
            Text(stringResource(R.string.export_phone_speed, stringResource(name)), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        FrameInterpolation.entries.forEach { method ->
            Section(stringResource(method.label), listOfNotNull(method.help, method.levels).map { stringResource(it) }, color)
        }
        Section(stringResource(R.string.export_motion_blur), listOf(stringResource(R.string.export_motion_blur_help)), color)
    }
}

@Composable
private fun Section(title: String, lines: List<String>, color: Color) {
    Column {
        Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        lines.forEach { Text(it, color = color, fontSize = 14.sp, lineHeight = 19.sp) }
    }
}

@Composable
internal fun aiSmoothEstimate(gmac: Double, level: SmoothLevel): AiEstimate? {
    val context = LocalContext.current
    val estimate by produceState<AiEstimate?>(null, gmac, level) {
        value = null
        val phone = AiDevice.phone(context)
        value = aiEstimate(phone, gmac, AiDevice.measured(level.model))
        AiDevice.benchmark(context, level.model)?.let { value = aiEstimate(phone, gmac, it) }
    }
    return estimate
}

@Composable
internal fun aiSmoothNote(estimate: AiEstimate?, level: SmoothLevel, clip: Boolean): String {
    val context = LocalContext.current
    val download = if (AiSmooth.installed(context, level)) null else stringResource(R.string.ai_download_once, formatBytes(AiSmooth.downloadBytes(level)))
    val time = estimate?.let { if (clip) it.text else it.extra } ?: stringResource(R.string.ai_checking_phone)
    return listOfNotNull(time, download).joinToString("\n")
}
