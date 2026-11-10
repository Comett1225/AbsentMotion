package app.absentmotion.ui.onboarding

import app.absentmotion.engine.EngineThread
import app.absentmotion.export.sizeLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.export.Codec
import app.absentmotion.i18n.AppLanguage
import app.absentmotion.i18n.applyLanguage
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.home.RefSwitch
import app.absentmotion.ui.home.TitleBar
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.foundation.layout.aspectRatio

const val ONBOARDING_LANGUAGE_CHOSEN = 1
const val ONBOARDING_DONE = 2

enum class OnboardingStep { Language, Look, Check }

data class FirstRun(val step: OnboardingStep) {
    companion object {
        fun start(stored: Int): OnboardingStep? = when {
            stored >= ONBOARDING_DONE -> null
            stored >= ONBOARDING_LANGUAGE_CHOSEN -> OnboardingStep.Check
            else -> OnboardingStep.Language
        }

        fun of(settings: AppSettings, forced: String?): FirstRun? {
            if (forced != null) settings.onboarding = when (forced) {
                "done" -> ONBOARDING_DONE
                "check" -> ONBOARDING_LANGUAGE_CHOSEN
                else -> 0
            }
            if (forced == "done") settings.lookChosen = true
            val step = start(settings.onboarding) ?: (if (settings.lookChosen) return null else OnboardingStep.Look)
            return FirstRun(step)
        }
    }
}

@Composable
fun Onboarding(settings: AppSettings, first: FirstRun, engine: () -> EngineThread, onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(first.step) }
    BackHandler(step == OnboardingStep.Check || step == OnboardingStep.Look && settings.onboarding < ONBOARDING_DONE) {
        step = if (step == OnboardingStep.Check) OnboardingStep.Look else OnboardingStep.Language
    }
    Box(Modifier.fillMaxSize().background(Am.HomeBg).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            when (step) {
                OnboardingStep.Language -> LanguageStep { language ->
                    settings.onboarding = ONBOARDING_LANGUAGE_CHOSEN
                    step = if (settings.lookChosen) OnboardingStep.Check else OnboardingStep.Look
                    applyLanguage(context, language)
                }
                OnboardingStep.Look -> LookStep(settings) {
                    if (settings.onboarding >= ONBOARDING_DONE) onDone() else step = OnboardingStep.Check
                }
                OnboardingStep.Check -> CheckStep(settings, engine) {
                    settings.chooseUpdates(settings.checkUpdates)
                    settings.onboarding = ONBOARDING_DONE
                    onDone()
                }
            }
        }
    }
}

@Composable
fun LookStep(settings: AppSettings, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().enterFade()) {
        Heading(stringResource(R.string.onboarding_look_title))
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), Alignment.Center) {
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                LookCard(settings, "AbsentMotion", app.absentmotion.ui.theme.Palette.AbsentMotion, R.string.theme_light, Modifier.weight(1f))
                LookCard(settings, "Dark", app.absentmotion.ui.theme.Palette.Dark, R.string.theme_dark, Modifier.weight(1f))
            }
        }
        Footer {
            Text(
                stringResource(R.string.onboarding_language_later), Modifier.fillMaxWidth().padding(bottom = 10.dp),
                color = Am.HomeMid, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            FilledButton(stringResource(R.string.action_continue), enabled = true) {
                settings.lookChosen = true
                onContinue()
            }
        }
    }
}

@Composable
private fun LookCard(settings: AppSettings, id: String, p: app.absentmotion.ui.theme.Palette, label: Int, modifier: Modifier) {
    val chosen = settings.theme == id
    val shape = RoundedCornerShape(16.dp)
    val ring by androidx.compose.animation.animateColorAsState(if (chosen) Am.HomeAccent else Am.HomeLine, app.absentmotion.ui.theme.Motion.fast(), label = "look ring")
    Column(
        modifier.clip(shape).border(2.dp, ring, shape)
            .selectable(chosen, role = Role.RadioButton) { if (!chosen) app.absentmotion.ui.theme.ThemeFade.change { settings.theme = id } }.padding(10.dp),
    ) {
        HomeSketch(p, Modifier.fillMaxWidth().aspectRatio(0.6f).clip(RoundedCornerShape(12.dp)))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (chosen) Icon(PanelIcons.Check, null, Modifier.size(20.dp), tint = Am.HomeAccent)
        }
    }
}

@Composable
private fun HomeSketch(p: app.absentmotion.ui.theme.Palette, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val u = size.width / 20f
        val corner = androidx.compose.ui.geometry.CornerRadius(u * 0.7f)
        fun box(c: Color, x: Float, y: Float, w: Float, h: Float, r: androidx.compose.ui.geometry.CornerRadius = corner) =
            drawRoundRect(c, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r)
        val pill = androidx.compose.ui.geometry.CornerRadius(u * 2f)
        drawRect(p.HomeBg)
        for (i in 0..2) box(p.HomeInk, u * 1.2f, u * (1.6f + i * 0.7f), u * 2.2f, u * 0.32f, androidx.compose.ui.geometry.CornerRadius(u))
        box(p.HomeInk, u * 6.5f, u * 1.85f, u * 7f, u * 0.9f, androidx.compose.ui.geometry.CornerRadius(u * 0.45f))
        box(p.Navy, u * 1.2f, u * 4.6f, u * 5.6f, u * 2.2f, pill)
        box(p.HomePanel, u * 7.4f, u * 4.6f, u * 5f, u * 2.2f, pill)
        box(p.HomeInk, u * 1.2f, u * 8.4f, u * 7.5f, u * 0.9f, androidx.compose.ui.geometry.CornerRadius(u * 0.45f))
        box(p.HomeFaint, u * 1.2f, u * 10.4f, u * 5f, u * 0.6f, androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
        var y = u * 11.8f
        val bar = size.height - u * 4.4f
        while (y + u * 5f < bar - u * 0.6f) {
            box(p.Card, u * 1.2f, y, size.width - u * 2.4f, u * 5f)
            box(p.HomeLine, u * 1.8f, y + u * 0.6f, u * 3.8f, u * 3.8f, androidx.compose.ui.geometry.CornerRadius(u * 0.5f))
            box(p.HomeInk, u * 6.4f, y + u * 1.1f, u * 7.2f, u * 0.8f, androidx.compose.ui.geometry.CornerRadius(u * 0.4f))
            box(p.HomeFaint, u * 6.4f, y + u * 2.6f, u * 4.6f, u * 0.55f, androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
            box(p.HomeFaint, u * 6.4f, y + u * 3.5f, u * 3.6f, u * 0.55f, androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
            for (d in 0..2) drawCircle(p.HomeFaint, u * 0.22f, androidx.compose.ui.geometry.Offset(size.width - u * 3.2f + d * u * 0.7f, y + u * 2.5f))
            y += u * 5.8f
        }
        drawRect(p.Card, androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width, size.height - bar))
        for (x in listOf(0.18f, 0.66f, 0.86f)) box(p.HomeMid, size.width * x - u * 0.7f, bar + u * 1.3f, u * 1.4f, u * 1.4f, androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
        drawCircle(p.HomeAccent, u * 2.1f, androidx.compose.ui.geometry.Offset(size.width * 0.42f, bar + u * 0.4f))
    }
}

@Composable
private fun LanguageStep(onContinue: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val top = remember { currentLanguage(context) }
    var chosen by rememberSaveable { mutableStateOf(top) }
    Column(Modifier.fillMaxSize().enterFade()) {
        Heading(stringResource(R.string.onboarding_language_title))
        LanguageList(top, chosen, Modifier.weight(1f)) { chosen = it }
        Footer {
            Text(
                stringResource(R.string.onboarding_language_later), Modifier.fillMaxWidth().padding(bottom = 10.dp),
                color = Am.HomeMid, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            FilledButton(stringResource(R.string.action_continue), enabled = true) { onContinue(chosen) }
        }
    }
}

@Composable
private fun CheckStep(settings: AppSettings, engine: () -> EngineThread, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<PhoneCheckResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    val finished = result != null || failed
    val running = job != null && !finished
    fun run() {
        job = scope.launch {
            try {
                result = runPhoneCheck(context.applicationContext, engine()) { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
    }
    fun skip() { job?.cancel(); onDone() }
    Column(Modifier.fillMaxSize().enterFade()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Heading(stringResource(R.string.onboarding_check_title))
            Text(
                stringResource(R.string.onboarding_check_body), Modifier.padding(horizontal = 20.dp),
                color = Am.HomeInk, fontSize = 15.sp, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(20.dp))
            Results(result, running) { progress }
            if (failed) Text(
                stringResource(R.string.onboarding_check_failed), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
        }
        Footer {
            UpdateChoice(settings)
            Spacer(Modifier.height(10.dp))
            val runLabel = stringResource(if (running) R.string.onboarding_check_running else R.string.onboarding_check_run)
            val skipLabel = stringResource(R.string.action_skip)
            if (finished) FilledButton(stringResource(R.string.action_continue), enabled = true, onClick = onDone)
            else FilledButton(runLabel, enabled = !running, onClick = ::run)
            Spacer(Modifier.height(6.dp))
            PlainButton(skipLabel, visible = !finished, onClick = ::skip)
        }
    }
}

@Composable
private fun UpdateChoice(settings: AppSettings) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.update_check_on_open), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.update_check_note), Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(8.dp))
        RefSwitch(settings.checkUpdates, enabled = true) { settings.chooseUpdates(it) }
    }
}

@Composable
private fun Results(result: PhoneCheckResult?, running: Boolean, progress: () -> Float) {
    Card {
        LinearProgressIndicator(
            progress, Modifier.fillMaxWidth().padding(bottom = 8.dp).alpha(if (running) 1f else 0f), color = Am.Navy, trackColor = Am.HomeLine,
        )
        Label(stringResource(R.string.onboarding_check_formats))
        for (format in result?.formats ?: Codec.entries.map { FormatSupport(it.label, 0, false) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(format.name, Modifier.weight(1f), color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        result == null -> UNKNOWN
                        format.maxShortSide <= 0 -> stringResource(R.string.onboarding_check_unsupported)
                        format.hardware -> stringResource(R.string.onboarding_check_up_to_hardware, sizeLabel(format.maxShortSide))
                        else -> stringResource(R.string.onboarding_check_up_to_software, sizeLabel(format.maxShortSide))
                    },
                    color = if (format.maxShortSide > 0) Am.HomeInk else Am.HomeMid, fontSize = 14.sp, textAlign = TextAlign.End,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Label(stringResource(R.string.onboarding_check_speed))
        val (word, note) = when (result?.speed) {
            null -> null to null
            PhoneSpeed.Fast -> R.string.onboarding_speed_fast to R.string.onboarding_speed_fast_note
            PhoneSpeed.Good -> R.string.onboarding_speed_good to
                if (result.formats.any { it.maxShortSide >= 2160 }) R.string.onboarding_speed_good_note else R.string.onboarding_speed_good_note_hd
            PhoneSpeed.Basic -> R.string.onboarding_speed_basic to R.string.onboarding_speed_basic_note
        }
        Text(word?.let { stringResource(it) } ?: UNKNOWN, color = if (word != null) Am.Navy else Am.HomeMid, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (note != null) Text(stringResource(note), Modifier.padding(top = 2.dp), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

private const val UNKNOWN = "\u2013"

@Composable
fun LanguageDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val current = remember { currentLanguage(context) }
    Dialog(onClose, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Column(Modifier.fillMaxSize().background(Am.SheetBg).navigationBarsPadding()) {
                TitleBar(stringResource(R.string.settings_language), HomeIcons.Close, stringResource(R.string.action_close), onClose)
                LanguageList(current, current, Modifier.weight(1f)) { language ->
                    onClose()
                    if (language != current) applyLanguage(context, language)
                }
            }
        }
    }
}

@Composable
private fun LanguageList(top: AppLanguage, chosen: AppLanguage, modifier: Modifier, onPick: (AppLanguage) -> Unit) {
    val ui = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    val order = remember(top) { AppLanguage.suggestedOrder(top) }
    LazyColumn(
        modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = top.tag) { LanguageRow(top, top == chosen, ui) { onPick(top) } }
        item(key = "others") {
            Text(
                stringResource(R.string.onboarding_language_others), Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
                color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
        items(order.drop(1), key = { it.tag }) { language -> LanguageRow(language, language == chosen, ui) { onPick(language) } }
    }
}

@Composable
private fun LanguageRow(language: AppLanguage, chosen: Boolean, ui: Locale, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val local = remember(language, ui) { Locale.forLanguageTag(when (language.tag) { "zh-TW" -> "zh-Hant"; "zh-CN" -> "zh-Hans"; else -> language.tag }).getDisplayName(ui).replaceFirstChar { it.titlecase(ui) } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(shape).background(Am.Card)
            .border(2.dp, if (chosen) Am.Navy else Color.Transparent, shape)
            .selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(language.nativeName, color = Am.HomeInk, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (!local.equals(language.nativeName, ignoreCase = true)) Text(local, color = Am.HomeMid, fontSize = 13.sp, maxLines = 1)
        }
        Box(Modifier.size(24.dp)) { if (chosen) Icon(PanelIcons.Check, null, Modifier.size(24.dp), tint = Am.Navy) }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp),
    color = Am.Navy, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
)

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(bottom = 4.dp), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(16.dp)) { content() }
}

@Composable
private fun Footer(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) { content() }
}

@Composable
private fun FilledButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).alpha(if (enabled) 1f else 0.55f).clip(RoundedCornerShape(14.dp)).background(Am.Navy)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Am.OnNavy, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun PlainButton(label: String, visible: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).alpha(if (visible) (if (enabled) 1f else 0.45f) else 0f).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = visible && enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}
