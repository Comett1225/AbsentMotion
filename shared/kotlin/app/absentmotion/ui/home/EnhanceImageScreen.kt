package app.absentmotion.ui.home

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.Feature
import app.absentmotion.R
import app.absentmotion.RiskAsk
import app.absentmotion.Tier
import app.absentmotion.ai.AiEnhance
import app.absentmotion.ai.AiDevice
import app.absentmotion.ai.aiEstimate
import app.absentmotion.ai.EnhanceMode
import app.absentmotion.ai.EnhanceOptions
import app.absentmotion.ai.imageFits
import app.absentmotion.ai.imageEstimateText
import app.absentmotion.export.formatBytes
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.rememberMediaPicker
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.tools.Compressed
import app.absentmotion.tools.PickedMedia
import app.absentmotion.tools.compressFolder
import app.absentmotion.tools.outputName
import app.absentmotion.tools.readPicked
import app.absentmotion.ui.RiskDialog
import app.absentmotion.ui.editor.effects.CompareImages
import app.absentmotion.ui.editor.effects.EnhanceCompare
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EnhanceImageScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work = rememberToolWork()
    var picked by remember { mutableStateOf<PickedMedia?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(EnhanceOptions(max = PlatformFeatures.desktop)) }
    var risk by remember { mutableStateOf<RiskAsk?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    val picker = rememberMediaPicker(imageOnly = true) { uri ->
        if (uri != null) scope.launch {
            val media = readPicked(context, uri)?.takeIf { !it.source.video }
            work.change { picked = media; unreadable = media == null }
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val current = work.step as? ToolStep.Done
        if (uri != null && current != null) work.saveTo(current, uri)
    }
    val source = picked?.source
    val fits = source != null && imageFits(source.width, source.height, options)
    val missing = options.models.filterNot { it.installed(context) }.sumOf { it.bytes }
    var estimate by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(options, source) {
        estimate = null
        if (source != null && fits) {
            val phone = AiDevice.phone(context)
            val gmac = options.gmacPerFrame(source.width, source.height)
            estimate = imageEstimateText(aiEstimate(phone, gmac, AiDevice.measured(options.model, phone.usesGpu)))
            AiDevice.benchmark(context, options.model)?.let { estimate = imageEstimateText(aiEstimate(phone, gmac, it)) }
        }
    }
    val download = if (missing > 0) stringResource(R.string.ai_download_once, formatBytes(missing)) else null
    val note = listOfNotNull(AiEnhance.imageCapText(options), estimate, download).joinToString("\n")
    val barNote = if (source != null && !fits) AiEnhance.imageCapText(options) else listOfNotNull(download, estimate).joinToString("\n")
    val done = work.step as? ToolStep.Done
    fun enhance() {
        val media = picked ?: return
        val chosen = options
        val warning = Tier.risk(Feature.AiEnhance).takeUnless { unlocked }
        if (warning != null) { risk = RiskAsk(listOf(warning)) { unlocked = true; enhance() }; return }
        work.start { report ->
            withContext(Dispatchers.IO) {
                for (model in chosen.models) if (!model.installed(context)) model.download(context) { report(it * 0.1f) }
                val declaration = MediaDeclaration(XmlNode("media", mapOf("uri" to media.uri.toString(), "width" to "${media.source.width}", "height" to "${media.source.height}", "orientation" to "${media.rotation}")))
                val output = AiEnhance.image(context, media.uri.toString(), declaration, chosen, { fraction, _ -> withContext(Dispatchers.Main) { report(0.1f + fraction * 0.9f) } }, upright = true)
                try {
                    val target = java.io.File(compressFolder(context).apply { mkdirs() }, outputName(media, "png", "enhanced"))
                    output.file.copyTo(target, overwrite = true)
                    Compressed(target, "image/png", false)
                } finally { output.file.delete() }
            }
        }
    }
    ToolSheet(stringResource(R.string.tools_enhance_image), { if (work.running) work.cancel() else onClose() }, bottom = {
        ToolBar(work, barNote, source != null && !fits, stringResource(R.string.compress_result), R.string.tools_enhance_running,
            stringResource(if (missing > 0) R.string.ai_download_and_start else R.string.start), HomeIcons.Compress, fits,
            onSave = if (PlatformFeatures.desktop) { result -> savePicker.launch(result.result.file.name) } else null, share = false, onStart = ::enhance)
    }, side = {
        SectionTitle(stringResource(R.string.ai_enhance))
        Chips(listOf(stringResource(R.string.ai_enhance_realistic), stringResource(R.string.ai_enhance_anime)), if (options.cartoon) 1 else 0, !work.running) { i -> work.change { options = options.copy(cartoon = i == 1) } }
        Text(stringResource(if (options.cartoon) R.string.ai_enhance_anime_hint else R.string.ai_enhance_realistic_hint), Modifier.padding(top = 6.dp).heightIn(min = (48f * LocalDensity.current.fontScale).dp), color = Am.HomeDim, fontSize = 14.sp)
        if (PlatformFeatures.desktop) {
            Label(stringResource(R.string.ai_enhance_quality))
            Chips(listOf(stringResource(R.string.ai_enhance_standard), stringResource(R.string.ai_enhance_max)), if (options.max) 1 else 0, !work.running) { i -> work.change { options = options.copy(max = i == 1) } }
        }
        Spacer(Modifier.height(12.dp))
        for (mode in EnhanceMode.entries.filter { it != EnhanceMode.Faces || PlatformFeatures.aiFaces }) {
            ChoiceRow(stringResource(mode.title), stringResource(mode.imageHint), options.mode == mode, !work.running) { work.change { options = options.copy(mode = mode) } }
        }
        if (!options.cartoon && !options.max) ChoiceRow(stringResource(R.string.ai_enhance_grain), null, options.grain, !work.running) { work.change { options = options.copy(grain = !options.grain) } }
        Text(note, Modifier.padding(top = 12.dp), color = if (source != null && !fits) Am.Accent else Am.HomeDim, fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, unreadable, !work.running, R.string.tools_enhance_pick) { picker.launch() }
        RiskDialog(risk, Am.Navy, Am.Warning, Am.Card) { risk = null; it?.proceed?.invoke() }
        if (done != null && picked != null) {
            SectionTitle(stringResource(R.string.ai_compare))
            EnhanceCompare(CompareImages(picked!!.uri.toString(), android.net.Uri.fromFile(done.result.file).toString(), picked!!.rotation, 0))
        }
    }
}
