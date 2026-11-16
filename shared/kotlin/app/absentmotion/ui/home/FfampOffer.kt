package app.absentmotion.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.media.MediaReader
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.Toast
import app.absentmotion.ui.theme.Am

internal class FfampSpeed(@StringRes val task: Int, val times: Double)

internal fun ffampSpeeds(desktop: Boolean = PlatformFeatures.desktop, os: String = System.getProperty("os.name").orEmpty().lowercase()): List<FfampSpeed> = when {
    !desktop -> listOf(FfampSpeed(R.string.ffamp_speed_open, 25.0), FfampSpeed(R.string.ffamp_speed_thumbnails, 2.4), FfampSpeed(R.string.ffamp_speed_sound, 1.2))
    "mac" in os -> listOf(FfampSpeed(R.string.ffamp_speed_open, 4.0), FfampSpeed(R.string.ffamp_speed_decode, 7.0), FfampSpeed(R.string.ffamp_speed_export, 1.7), FfampSpeed(R.string.ffamp_speed_tools, 2.8))
    "windows" in os -> listOf(FfampSpeed(R.string.ffamp_speed_open, 5.0), FfampSpeed(R.string.ffamp_speed_thumbnails, 2.5), FfampSpeed(R.string.ffamp_speed_decode, 1.3), FfampSpeed(R.string.ffamp_speed_tools, 3.8))
    "linux" in os -> listOf(FfampSpeed(R.string.ffamp_speed_open, 29.0), FfampSpeed(R.string.ffamp_speed_tools, 13.0))
    else -> emptyList()
}

object FfampOffer {
    fun pending(settings: AppSettings) = PlatformFeatures.ffamp && !settings.ffampAsked && System.getProperty("am.mediaReader") == null
}

@Composable
fun FfampOfferDialog(settings: AppSettings) {
    val context = LocalContext.current
    fun choose(use: Boolean) {
        settings.ffamp = use
        settings.ffampAsked = true
        if (use && !MediaReader.useNow(true)) Toast.makeText(context, context.getString(R.string.ffamp_offer_later), Toast.LENGTH_LONG).show()
    }
    Dialog({ choose(false) }, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp), Alignment.Center) {
            FfampOfferCard(ffampSpeeds(), !PlatformFeatures.desktop, { choose(true) }, { choose(false) })
        }
    }
}

@Composable
internal fun FfampOfferCard(speeds: List<FfampSpeed>, formats: Boolean, onUse: () -> Unit, onKeep: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val number = java.text.NumberFormat.getNumberInstance()
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(shape).background(Am.Card)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)) {
            Text(stringResource(R.string.ffamp_offer_title), Modifier.semantics { heading() }, color = Am.Navy, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.ffamp_offer_body), Modifier.padding(top = 8.dp), color = Am.HomeMid, fontSize = 15.sp, lineHeight = 21.sp)
            if (speeds.isNotEmpty()) {
                Text(stringResource(R.string.ffamp_offer_speeds), Modifier.padding(top = 18.dp, bottom = 2.dp), color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                speeds.forEach { speed ->
                    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.HomeBg).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(speed.task), Modifier.weight(1f), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 20.sp)
                        Text(stringResource(R.string.ffamp_speed_value, number.format(speed.times)), Modifier.padding(start = 12.dp), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (formats) Text(stringResource(R.string.ffamp_offer_formats), Modifier.padding(top = 12.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)
            Text(stringResource(R.string.ffamp_offer_note), Modifier.padding(top = 14.dp), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Am.HomeLine))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OfferButton(stringResource(R.string.ffamp_offer_use), true, onUse)
            OfferButton(stringResource(R.string.ffamp_offer_keep), false, onKeep)
        }
    }
}

@Composable
private fun OfferButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(if (primary) Am.Accent else Am.HomeBg)
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        Alignment.Center,
    ) { Text(text, color = if (primary) Am.OnAccent else Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
}
