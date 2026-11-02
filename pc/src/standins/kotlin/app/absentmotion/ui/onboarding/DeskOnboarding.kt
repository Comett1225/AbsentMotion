package app.absentmotion.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskBack
import app.absentmotion.i18n.AppLanguage
import app.absentmotion.i18n.applyLanguage
import app.absentmotion.i18n.phoneLanguage
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.deskScrollbar
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.home.DeskButton
import app.absentmotion.ui.home.deskPress
import app.absentmotion.ui.theme.Am
import java.util.Locale

@Composable
fun DeskOnboarding(settings: AppSettings, first: FirstRun, onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(first.step) }
    val initial = remember { phoneLanguage(context) }
    var chosen by remember { mutableStateOf(initial) }
    DeskBack.Handler(step == OnboardingStep.Check) { step = OnboardingStep.Language }
    Box(Modifier.fillMaxSize().background(Am.HomeBg), Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 32.dp, vertical = 36.dp)) {
            when (step) {
                OnboardingStep.Language -> {
                    Text(stringResource(R.string.desk_welcome_title), color = Am.Navy, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.desk_welcome_body), Modifier.padding(top = 8.dp), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 21.sp)
                    Text(stringResource(R.string.onboarding_language_title), Modifier.padding(top = 28.dp, bottom = 10.dp), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    LanguageGrid(initial, chosen, Modifier.weight(1f)) { chosen = it }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (chosen != initial) R.string.desk_language_restart else R.string.onboarding_language_later), Modifier.weight(1f),
                            color = if (chosen != initial) Am.HomeAccent else Am.HomeMid, fontSize = 13.sp,
                        )
                        DeskButton(stringResource(R.string.action_continue), {
                            settings.onboarding = ONBOARDING_LANGUAGE_CHOSEN
                            applyLanguage(context, chosen)
                            step = OnboardingStep.Check
                        }, primary = true, minWidth = 140.dp, height = 40.dp)
                    }
                }
                OnboardingStep.Check -> {
                    var updates by remember { mutableStateOf(settings.checkUpdates) }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.desk_welcome_updates_title), color = Am.Navy, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.update_check_on_open), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.update_check_note), Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        ThinSwitch(updates, enabled = true, offTrack = Am.SwitchOff, onTrack = Am.HomeAccent.copy(alpha = 0.5f), onThumb = Am.HomeAccent, onChange = { updates = it })
                    }
                    Row(Modifier.padding(top = 24.dp).fillMaxWidth(), Arrangement.spacedBy(10.dp, Alignment.End)) {
                        DeskButton(stringResource(R.string.topbar_back), { step = OnboardingStep.Language }, height = 40.dp)
                        DeskButton(stringResource(R.string.desk_welcome_start), {
                            settings.chooseUpdates(updates)
                            settings.onboarding = ONBOARDING_DONE
                            onDone()
                        }, primary = true, minWidth = 140.dp, height = 40.dp)
                    }
                    Spacer(Modifier.weight(1.4f))
                }
            }
        }
    }
}

@Composable
private fun LanguageGrid(top: AppLanguage, chosen: AppLanguage, modifier: Modifier, onPick: (AppLanguage) -> Unit) {
    val ui = remember { Locale.getDefault() }
    val order = remember(top) { AppLanguage.suggestedOrder(top) }
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        GridCells.Adaptive(250.dp), modifier.fillMaxWidth().deskScrollbar(grid, Am.Navy).selectableGroup(), grid,
        contentPadding = PaddingValues(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(order, key = { it.tag }) { language ->
            val on = language == chosen
            val local = remember(language) { Locale.forLanguageTag(language.tag).getDisplayName(ui).replaceFirstChar { it.titlecase(ui) } }
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(shape).background(Am.Card).border(2.dp, if (on) Am.Navy else Color.Transparent, shape).deskPress(12.dp)
                    .selectable(on, role = Role.RadioButton) { onPick(language) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(language.nativeName, color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (!local.equals(language.nativeName, ignoreCase = true)) Text(local, color = Am.HomeMid, fontSize = 12.sp, maxLines = 1)
                }
                Box(Modifier.size(22.dp)) { if (on) Icon(PanelIcons.Check, null, Modifier.size(22.dp), tint = Am.Navy) }
            }
        }
    }
}
