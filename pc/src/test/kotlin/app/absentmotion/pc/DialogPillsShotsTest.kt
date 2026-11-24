package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.Risk
import app.absentmotion.RiskAsk
import app.absentmotion.desk.DeskAppState
import app.absentmotion.i18n.UiText
import app.absentmotion.ui.RiskDialog
import app.absentmotion.ui.home.ClosedDialog
import app.absentmotion.ui.home.DialogPill
import app.absentmotion.ui.home.OpenFailedDialog
import app.absentmotion.ui.home.Outdated
import app.absentmotion.ui.home.UpdateAskDialog
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Palette
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DialogPillsShotsTest {
    init { System.setProperty("am.data", File("build/dialog-pills-data").absolutePath) }
    private val dir = File("build/ui-shots/pills").also { it.mkdirs() }
    private val palettes = listOf("absentmotion" to Palette.AbsentMotion, "dark" to Palette.Dark, "daylight" to Palette.Daylight)
    private val sizes = listOf("desk" to Size(1280f, 800f), "phone" to Size(360f, 780f))

    private fun each(name: String, back: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Am.HomeBg)) }, check: ComposeUiTest.() -> Unit = {}, content: @Composable () -> Unit) {
        val before = Am.palette
        try {
            for ((look, palette) in palettes) for ((form, size) in sizes) {
                Am.palette = palette
                runSkikoComposeUiTest(size = size) {
                    setContent { back(); content() }
                    waitForIdle()
                    check()
                    ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("$name-$look-$form.png"))
                }
            }
        } finally {
            Am.palette = before
        }
    }

    @Test fun recoveryKeepsItsAnswers() {
        val calls = mutableListOf<String>()
        each("recovery", check = {
            onNodeWithText("Restore").assertHasClickAction().performClick()
            onNodeWithText("Discard").performClick()
        }) { ClosedDialog(null, "Example project", null, { calls += "restore" }, { calls += "discard" }, {}) }
        assertEquals(List(6) { listOf("restore", "discard") }.flatten(), calls)
    }

    @Test fun openFailedWithReportAndDownload() {
        each("open-failed") { OpenFailedDialog({}, Outdated("5.2.2", "5.2.3", "https://example.com")) {} }
    }

    @Test fun updateQuestion() {
        val app = DeskAppState()
        each("update-ask") { UpdateAskDialog(app.settings) }
    }

    @Test fun editorRiskAndLongLabels() {
        val ask = RiskAsk(listOf(Risk("gpu", UiText(R.string.ai_gpu_warning)))) {}
        val editorBack: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Am.PreviewBg)) }
        each("risk-editor", editorBack) { RiskDialog(ask, Am.OnDark, Am.Warning, Am.Field) {} }
        var started = 0
        each("long-labels", editorBack, check = {
            onNodeWithText("Start").assertIsNotEnabled().performClick()
            onNodeWithText("Allow 300,000").performClick()
        }) {
            AlertDialog(
                onDismissRequest = {}, title = { Text(stringResource(R.string.model_detail_ask_title)) },
                text = { Text(stringResource(R.string.model_detail_ask_body, "500,000", "100,000", "300,000")) },
                dismissButton = {
                    DialogPill(stringResource(R.string.model_detail_keep, "100,000"), primary = false, editor = true) {}
                    DialogPill(stringResource(R.string.start), enabled = false) { started++ }
                },
                confirmButton = { DialogPill(stringResource(R.string.model_detail_allow, "300,000")) {} },
                containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
            )
        }
        assertEquals(0, started)
    }
}
