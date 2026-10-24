package app.absentmotion.ui.tutorial

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import app.absentmotion.platform.Log
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.TextIcons
import app.absentmotion.ui.home.CardGap
import app.absentmotion.ui.home.LinkRow
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun TutorialHost(
    editor: EditorState?,
    store: ProjectStore,
    settings: AppSettings,
    homeFree: Boolean,
    onOpen: (String) -> Unit,
    onCloseEditor: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(editor)
    val tour = Tutorial.tour
    var switchTo by remember { mutableStateOf<Boolean?>(null) }
    var afterClose by remember { mutableStateOf<Boolean?>(null) }
    var exitAsk by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    suspend fun begin(resume: Boolean) {
        val saved = settings.tutorialProject?.takeIf { resume && withContext(Dispatchers.IO) { store.exists(it) } }
        val id = saved ?: try {
            withContext(Dispatchers.IO) { store.create(Scene.create(context.getString(R.string.tutorial_project_name), SIDE, SIDE, FPS, BLACK)) }
        } catch (e: java.io.IOException) {
            Log.w("AMTutorial", "cannot create the practice project", e)
            Toast.makeText(context, context.getString(R.string.home_create_project_failed), Toast.LENGTH_LONG).show()
            return
        }
        settings.tutorialProject = id
        settings.tutorialAsk = Tutorial.NEVER
        Tutorial.offerDismissed = true
        Tutorial.tour = Tour(startAt = if (resume) settings.tutorialStep else null).also { settings.tutorialStep = it.step.id }
        if (current?.projectId != id) onOpen(id)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Tutorial.request }.collect { request ->
            if (request == null) return@collect
            Tutorial.request = null
            val open = current
            if (open != null && open.projectId != settings.tutorialProject) switchTo = request else begin(request)
        }
    }
    LaunchedEffect(afterClose, editor == null) {
        val resume = afterClose ?: return@LaunchedEffect
        if (editor != null) return@LaunchedEffect
        afterClose = null
        scope.launch { begin(resume) }
    }
    LaunchedEffect(deleting, editor == null) {
        val id = deleting ?: return@LaunchedEffect
        if (editor != null) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { store.delete(id, context.getExternalFilesDir(null)?.resolve("ai")) }.onFailure { Log.w("AMTutorial", "cannot delete the practice project", it) } }
        if (settings.tutorialProject == id) settings.tutorialProject = null
        deleting = null
        onChanged()
    }

    fun finish() {
        Tutorial.tour = null
        settings.tutorialStep = null
        deleteAsk = settings.tutorialProject
    }

    val practice = editor != null && editor.projectId == settings.tutorialProject
    if (tour != null && editor != null && practice) {
        TourRunner(tour, editor, settings, onFinished = ::finish, onExit = { exitAsk = true })
    } else if (tour != null && editor == null && homeFree) {
        PausedBanner(onContinue = { Tutorial.start(resume = true) }, onExit = { exitAsk = true })
    } else if (tour == null && editor == null && homeFree && !Tutorial.offerDismissed && settings.tutorialAsk == Tutorial.ASK) {
        OfferDialog(
            onStart = { Tutorial.start(resume = false) },
            onLater = { Tutorial.offerDismissed = true },
            onNever = { settings.tutorialAsk = Tutorial.NEVER; Tutorial.offerDismissed = true },
        )
    }

    val dark = editor != null
    switchTo?.let { resume ->
        Question(dark, R.string.tutorial_switch_title, R.string.tutorial_switch_body, R.string.tutorial_switch_yes, R.string.cancel,
            onYes = { switchTo = null; afterClose = resume; onCloseEditor() }, onNo = { switchTo = null })
    }
    if (exitAsk) Question(dark, R.string.tutorial_exit_title, R.string.tutorial_exit_body, R.string.tutorial_exit_leave, R.string.tutorial_exit_stay,
        onYes = { exitAsk = false; Tutorial.tour = null; deleteAsk = settings.tutorialProject }, onNo = { exitAsk = false })
    deleteAsk?.let { id ->
        Question(dark, R.string.tutorial_delete_title, R.string.tutorial_delete_body, R.string.delete, R.string.tutorial_delete_keep,
            onYes = {
                deleteAsk = null
                deleting = id
                if (current?.projectId == id) onCloseEditor()
            },
            onNo = { deleteAsk = null },
        )
    }
}

private data class TourView(
    val number: Int,
    val count: Int,
    val step: TourStep,
    val note: Int?,
    val hint: Int?,
    val targets: List<String>,
    val canDoIt: Boolean,
) {
    val last: Boolean get() = number == count

    companion object {
        fun of(tour: Tour) = TourView(tour.index + 1, tour.steps.size, tour.step, tour.note, tour.hint(), tour.targets(), tour.canDoIt)
    }
}

private class TourDriver(
    private val tour: Tour,
    private val editor: EditorState,
    private val hands: TourHands,
    private val settings: AppSettings,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onFinished: () -> Unit,
) {
    var view by mutableStateOf(TourView.of(tour))
        private set
    private var busy = false

    private fun settle() {
        view = TourView.of(tour)
        if (tour.finished) onFinished() else if (settings.tutorialStep != tour.step.id) settings.tutorialStep = tour.step.id
    }

    fun feed(facts: TourFacts = tourFacts(editor, true)) {
        if (busy || Tutorial.tour !== tour) return
        val actions = tour.observe(facts)
        settle()
        run(actions)
    }

    private fun run(actions: List<TourAction>) {
        if (actions.isEmpty()) return
        busy = true
        scope.launch {
            try {
                for (action in actions) hands.perform(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AMTutorial", "cannot do $actions", e)
            } finally {
                busy = false
            }
            feed()
        }
    }

    fun next() {
        tour.next()
        settle()
        feed()
    }

    fun doIt() {
        if (busy) return
        val actions = tour.doIt()
        settle()
        if (actions.isEmpty()) feed() else run(actions)
    }

    fun pickerClosed(picked: Boolean) {
        if (busy) return
        val actions = tour.pickerClosed(picked)
        settle()
        run(actions)
    }
}

@Composable
private fun TourRunner(tour: Tour, editor: EditorState, settings: AppSettings, onFinished: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finished by rememberUpdatedState(onFinished)
    val driver = remember(tour, editor) { TourDriver(tour, editor, TourHands(context, editor), settings, scope) { finished() } }
    val view = driver.view
    var collapsed by remember(view.number) { mutableStateOf(false) }

    LaunchedEffect(driver) { snapshotFlow { tourFacts(editor, true) }.collect { driver.feed(it) } }
    DisposableEffect(driver) {
        val listener: (Boolean) -> Unit = { driver.pickerClosed(it) }
        Tutorial.listener = listener
        onDispose { if (Tutorial.listener === listener) Tutorial.listener = null }
    }

    val found = view.targets.mapNotNull { Tutorial.anchors[it] }
    val hole = if (view.step.union) found.reduceOrNull { a, b -> androidx.compose.ui.geometry.Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) } else found.firstOrNull()
    Spotlight(hole, dim = hole != null || view.step.centred, centred = view.step.centred) {
        ScaledFonts {
            if (collapsed) Pill(view) { collapsed = false }
            else CoachCard(view, onCollapse = { collapsed = true }, onExit = onExit, onNext = driver::next, onDoIt = driver::doIt)
        }
    }
}

@Composable
private fun ScaledFonts(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = Tutorial.fontScale ?: LocalConfiguration.current.fontScale
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale.coerceIn(0.85f, 2f)), content = content)
}

@Composable
private fun Spotlight(hole: androidx.compose.ui.geometry.Rect?, dim: Boolean, centred: Boolean, card: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val insets = WindowInsets.safeDrawing
    val accent = Am.Accent
    val density = LocalDensity.current
    val pad = with(density) { 6.dp.toPx() }
    val local = hole?.let { Area(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y).grown(pad) }
    Layout(
        content = card,
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }.drawBehind {
            val corner = CornerRadius(10.dp.toPx())
            if (local == null) {
                if (dim) drawRect(SCRIM)
                return@drawBehind
            }
            val topLeft = Offset(local.left, local.top)
            val size = Size(local.width, local.height)
            val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(local.left, local.top, local.right, local.bottom, corner)) }
            if (dim) clipPath(path, ClipOp.Difference) { drawRect(SCRIM) }
            drawRoundRect(accent, topLeft, size, corner, style = Stroke(2.5.dp.toPx()))
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val margin = 12.dp.toPx()
        val area = Area(
            insets.getLeft(this, layoutDirection) + margin, insets.getTop(this) + margin,
            width - insets.getRight(this, layoutDirection) - margin, height - insets.getBottom(this) - margin,
        )
        val (region, side) = CardPlacement.region(area, local?.clampedTo(Area(0f, 0f, width, height)), 8.dp.toPx(), 260.dp.toPx(), 150.dp.toPx())
        val cardWidth = minOf(region.width, 420.dp.toPx()).roundToInt().coerceAtLeast(0)
        val placeable = measurables.first().measure(Constraints(maxWidth = cardWidth, maxHeight = region.height.roundToInt().coerceAtLeast(0)))
        val (x, y) = CardPlacement.position(region, side, local, placeable.width.toFloat(), placeable.height.toFloat(), centred)
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
    }
}

@Composable
private fun CoachCard(view: TourView, onCollapse: () -> Unit, onExit: () -> Unit, onNext: () -> Unit, onDoIt: () -> Unit) {
    val step = view.step
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().shadow(10.dp, shape).clip(shape).background(Am.Field).border(1.dp, Am.Line, shape)
            .pointerInput(Unit) {}.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tutorial_step_count, view.number, view.count), Modifier.weight(1f), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
            CardIcon(TextIcons.Down, stringResource(R.string.tutorial_hide), onCollapse)
            CardIcon(ChromeIcons.Close, stringResource(R.string.tutorial_exit), onExit)
        }
        Column(Modifier.padding(end = 10.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Am.Line)) {
                Box(Modifier.fillMaxWidth(view.number.toFloat() / view.count).fillMaxHeight().background(Am.Accent))
            }
            Column(Modifier.padding(top = 10.dp).weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                view.note?.let { Text(stringResource(it), Modifier.padding(bottom = 4.dp), color = Am.Accent, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium) }
                Text(stringResource(step.title), Modifier.semantics { heading() }, color = Am.OnDark, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(step.body), Modifier.padding(top = 4.dp), color = Am.OnDark, fontSize = 15.sp, lineHeight = 21.sp)
                view.hint?.let { Text(stringResource(it), Modifier.padding(top = 6.dp), color = Am.Accent, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (view.canDoIt) {
                    CardButton(stringResource(R.string.tutorial_skip_step), filled = false, Modifier.weight(1f), onNext)
                    CardButton(stringResource(R.string.tutorial_do_it), filled = true, Modifier.weight(1f), onDoIt)
                } else {
                    Spacer(Modifier.weight(1f))
                    CardButton(stringResource(if (view.last) R.string.tutorial_finish else R.string.tutorial_next), filled = true, Modifier.weight(1f), onNext)
                }
            }
        }
    }
}

@Composable
private fun Pill(view: TourView, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val label = stringResource(R.string.tutorial_pill, view.number, view.count)
    Row(
        Modifier.shadow(8.dp, shape).clip(shape).background(Am.Field).border(1.dp, Am.Accent, shape)
            .clickable(onClickLabel = stringResource(R.string.tutorial_show), role = Role.Button, onClick = onOpen).heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Icon(TextIcons.Up, null, Modifier.padding(start = 6.dp).size(18.dp), tint = Am.Accent)
    }
}

@Composable
private fun CardIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick, role = Role.Button, onClickLabel = label), Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = Am.OnDark)
    }
}

@Composable
private fun CardButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = 44.dp).clip(shape).then(if (filled) Modifier.background(Am.Accent) else Modifier.border(1.dp, Am.Line, shape))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        Alignment.Center,
    ) {
        Text(label, color = if (filled) Am.OnAccent else Am.OnDark, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PausedBanner(onContinue: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 104.dp), Alignment.BottomCenter) {
        ScaledFonts {
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().shadow(8.dp, shape).clip(shape).background(Am.Card).border(1.dp, Am.HomeLine, shape)
                    .pointerInput(Unit) {}.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tutorial_paused), Modifier.weight(1f), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 20.sp)
                TextButton(onExit) { Text(stringResource(R.string.tutorial_paused_exit), color = Am.Navy, fontSize = 14.sp) }
                TextButton(onContinue) { Text(stringResource(R.string.tutorial_paused_continue), color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun OfferDialog(onStart: () -> Unit, onLater: () -> Unit, onNever: () -> Unit) {
    Dialog(onLater) {
        ScaledFonts {
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).clip(shape).background(Am.Card).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(stringResource(R.string.tutorial_offer_title), Modifier.semantics { heading() }, color = Am.Navy, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.tutorial_offer_body), Modifier.padding(top = 8.dp, bottom = 18.dp), color = Am.HomeInk, fontSize = 15.sp, lineHeight = 21.sp)
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Am.Navy).clickable(role = Role.Button, onClick = onStart).padding(10.dp), Alignment.Center) {
                    Text(stringResource(R.string.tutorial_offer_start), color = Am.OnNavy, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(6.dp))
                OfferLink(stringResource(R.string.tutorial_offer_later), onLater)
                OfferLink(stringResource(R.string.tutorial_offer_never), onNever)
            }
        }
    }
}

@Composable
private fun OfferLink(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(8.dp), Alignment.Center) {
        Text(label, color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Question(dark: Boolean, title: Int, body: Int, yes: Int, no: Int, onYes: () -> Unit, onNo: () -> Unit) {
    val ink = if (dark) Am.OnDark else Am.HomeInk
    val button = if (dark) Am.Accent else Am.Navy
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(stringResource(title), color = ink) },
        text = { Text(stringResource(body), color = ink) },
        confirmButton = { TextButton(onYes) { Text(stringResource(yes), color = button) } },
        dismissButton = { TextButton(onNo) { Text(stringResource(no), color = button) } },
        containerColor = if (dark) Am.Field else Am.Card,
    )
}

@Composable
fun TutorialRows(settings: AppSettings) {
    if (settings.tutorialStep != null) {
        LinkRow(stringResource(R.string.tutorial_settings_continue)) { Tutorial.start(resume = true) }
        Spacer(Modifier.height(CardGap))
        LinkRow(stringResource(R.string.tutorial_settings_restart)) { Tutorial.start(resume = false) }
    } else {
        LinkRow(stringResource(R.string.tutorial_settings_start)) { Tutorial.start(resume = false) }
    }
}

private val SCRIM = Color.Black.copy(alpha = 0.55f)
private const val SIDE = 1080
private const val FPS = 30
private const val BLACK = 0xFF000000.toInt()
