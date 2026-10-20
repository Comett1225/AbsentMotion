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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import app.absentmotion.ui.home.TitleBar
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

const val ONBOARDING_LANGUAGE_CHOSEN = 1
const val ONBOARDING_DONE = 2

enum class OnboardingStep { Language, Check }

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
            val step = start(settings.onboarding) ?: return null
            return FirstRun(step)
        }
    }
}

@Composable
fun Onboarding(settings: AppSettings, first: FirstRun, engine: () -> EngineThread, onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(first.step) }
    BackHandler(step == OnboardingStep.Check) { step = OnboardingStep.Language }
    Box(Modifier.fillMaxSize().background(Am.HomeBg).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            when (step) {
                OnboardingStep.Language -> LanguageStep { language ->
                    settings.onboarding = ONBOARDING_LANGUAGE_CHOSEN
                    step = OnboardingStep.Check
                    applyLanguage(context, language)
                }
                OnboardingStep.Check -> CheckStep(engine) {
                    settings.onboarding = ONBOARDING_DONE
                    onDone()
                }
            }
        }
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
private fun CheckStep(engine: () -> EngineThread, onDone: () -> Unit) {
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
    val local = remember(language, ui) { Locale.forLanguageTag(language.tag).getDisplayName(ui).replaceFirstChar { it.titlecase(ui) } }
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(shape).background(Am.Card)
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
