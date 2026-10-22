package app.absentmotion.ui.export

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
import app.absentmotion.ui.onboarding.PhoneSpeed
import app.absentmotion.ui.onboarding.phoneCheckResult

@Composable
internal fun SmoothingHelp(color: Color, modifier: Modifier = Modifier) {
    val report by DeviceCapabilities.of(LocalContext.current).report.collectAsState()
    val speed = report?.let { phoneCheckResult(it, Build.VERSION.MEDIA_PERFORMANCE_CLASS).speed }
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
