package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.model.Scene
import app.absentmotion.platform.Uri
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalTestApi::class)
class DeskAddedEffectsTest {
    @get:Rule val data = TemporaryFolder()

    @Test fun pickerAddsACreatorFolderAndHoldMenusControlWhereEffectsAppear() {
        val previousData = System.getProperty("am.data")
        val previousPick = System.getProperty("am.pick")
        System.setProperty("am.data", data.root.absolutePath)
        try {
            runSkikoComposeUiTest(size = Size(1280f, 900f)) {
                val app = DeskAppState()
                app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
                val sample = """{"id":"sample-tint","name":"Sample Tint","author":"A Painter","category":"color","params":[],"sksl":"uniform shader image; half4 main(float2 p) { return image.eval(p) * half4(1, 0.5, 1, 1); }"}"""
                val broken = sample.replace("sample-tint", "broken-tint").replace("Sample Tint", "Broken Tint").replace("image.eval(p)", "missing(p)")
                val pack = data.newFile("tints.abmfx")
                ZipOutputStream(pack.outputStream()).use { zip ->
                    for ((name, json) in listOf("tint.json" to sample, "broken.json" to broken)) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(json.toByteArray())
                        zip.closeEntry()
                    }
                }
                System.setProperty("am.pick", pack.absolutePath)
                val scene = Scene.parse("""<scene width="320" height="240" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect"/></scene>""")
                setContent { DeskApp(app) }
                try {
                    runBlocking { app.openNow(app.store.create(scene)); app.engine.awaitEffects() }
                    runOnIdle { app.editor!!.selectedId = 1; app.editor!!.effectBrowserOpen = true }
                    onNodeWithText(Strings.get(R.string.added_effect_category)).performClick()
                    onNodeWithText(Strings.get(R.string.added_effect_card)).assertIsDisplayed()
                    onNodeWithText(Strings.get(R.string.added_effect_empty_title)).assertIsDisplayed()
                    onNodeWithText(Strings.get(R.string.added_effect_empty_body)).assertIsDisplayed()

                    onNodeWithText(Strings.get(R.string.added_effect_card)).performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText(Strings.get(R.string.added_effect_question)).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText(Strings.get(R.string.added_effect_question)).assertIsDisplayed()
                    onNodeWithText(Strings.get(R.string.added_effect_works)).assertIsDisplayed()
                    onNodeWithText(Strings.get(R.string.added_effect_does_not_work)).assertIsDisplayed()
                    val stored = File(app.context.filesDir, "effects-added/a-painter.sample-tint.json")
                    assertFalse(stored.exists())
                    onNodeWithText(Strings.get(R.string.added_effect_add)).performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("A Painter").fetchSemanticsNodes().size == 1 && stored.isFile }
                    onNodeWithText("Sample Tint").assertDoesNotExist()
                    onNodeWithText("A Painter").performClick()
                    onNodeWithText("A Painter").assertIsDisplayed()
                    onNodeWithText("Sample Tint").assertIsDisplayed()
                    onNodeWithContentDescription(Strings.get(R.string.topbar_back)).performClick()
                    onNodeWithText("Sample Tint").assertDoesNotExist()
                    onNodeWithText("A Painter").performClick()
                    assertTrue(stored.resolveSibling("a-painter.sample-tint.webp").isFile)
                    assertFalse(stored.resolveSibling("a-painter.broken-tint.json").exists())

                    val effectId = "user.a-painter.sample-tint"
                    val color = app.engine.effects.value.categories.toMap().getValue("color")
                    val badge = Strings.get(R.string.added_effect_badge)
                    val effectTile = onNode(hasText("Sample Tint") and hasContentDescription(badge))
                    val creatorTile = onNode(hasText("A Painter") and hasContentDescription(badge))
                    val back = onNodeWithContentDescription(Strings.get(R.string.topbar_back))
                    val showInColor = Strings.get(R.string.added_effect_show_in_category, color)
                    val removeFromColor = Strings.get(R.string.added_effect_remove_from_category, color)
                    assertFalse(app.engine.effects.value.listed("color").any { it.id == effectId })
                    effectTile.assertIsDisplayed().performTouchInput { longClick() }
                    onNodeWithText(showInColor).performClick()
                    waitUntil { app.engine.effects.value.listed("color").any { it.id == effectId } }
                    back.performClick()
                    back.performClick()
                    onNodeWithText(color).performClick()
                    val colorEffects = app.engine.effects.value.listed("color")
                    val firstBuiltIn = colorEffects.filterNot { it.isAdded }.minBy { it.name.lowercase() }
                    onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(firstBuiltIn.name))).performScrollToIndex(colorEffects.lastIndex)
                    effectTile.assertIsDisplayed().performTouchInput { longClick() }
                    onNodeWithText(removeFromColor).performClick()
                    waitUntil { app.engine.effects.value.listed("color").none { it.id == effectId } }
                    effectTile.assertDoesNotExist()
                    back.performClick()

                    val added = onNodeWithText(Strings.get(R.string.added_effect_category))
                    val showCreator = Strings.get(R.string.added_effect_show_creator_category)
                    val removeCreator = Strings.get(R.string.added_effect_remove_creator_category)
                    added.performClick()
                    creatorTile.assertIsDisplayed().performTouchInput { longClick() }
                    onNodeWithText(showCreator).performClick()
                    waitUntil { "a-painter" in app.engine.effects.value.shownAddedCreators }
                    back.performClick()
                    creatorTile.performScrollTo().assertIsDisplayed()
                    val creatorBounds = creatorTile.fetchSemanticsNode().boundsInRoot
                    val addedBounds = added.fetchSemanticsNode().boundsInRoot
                    assertTrue(creatorBounds.top > addedBounds.top ||
                        creatorBounds.top == addedBounds.top && creatorBounds.left > addedBounds.left)
                    creatorTile.performClick()
                    effectTile.assertIsDisplayed()
                    back.performClick()
                    creatorTile.performTouchInput { longClick() }
                    onNodeWithText(removeCreator).performClick()
                    waitUntil { "a-painter" !in app.engine.effects.value.shownAddedCreators }
                    creatorTile.assertDoesNotExist()
                    added.performClick()
                    creatorTile.performClick()

                    onNodeWithText("Sample Tint").performTouchInput { longClick() }
                    val remove = Strings.get(R.string.added_effect_remove)
                    onNodeWithText(remove).performClick()
                    onNodeWithText(Strings.get(R.string.added_effect_remove_question, "Sample Tint")).assertIsDisplayed()
                    onNodeWithText(remove).performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        app.engine.effects.value.find("user.a-painter.sample-tint") == null &&
                            onAllNodesWithText(Strings.get(R.string.added_effect_empty_title)).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Sample Tint").assertDoesNotExist()
                    onNodeWithText(Strings.get(R.string.added_effect_empty_title)).assertIsDisplayed()
                    assertFalse(stored.exists())
                } finally {
                    runBlocking { app.closeEditor() }
                    app.engine.close()
                }
            }
        } finally {
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
            if (previousPick == null) System.clearProperty("am.pick") else System.setProperty("am.pick", previousPick)
        }
    }

    @Test fun openingABundleAsksOnceAndInstallsItsEffects() {
        val previousData = System.getProperty("am.data")
        System.setProperty("am.data", data.root.absolutePath)
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            runSkikoComposeUiTest(size = Size(1280f, 900f)) {
                val app = DeskAppState()
                app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
                val effect = """{"id":"light","name":"Bundle Light","author":"A Painter","category":"color","version":3,"params":[],"sksl":"uniform shader image; half4 main(float2 p) { return image.eval(p); }"}"""
                val scene = """<scene title="Bundle Test" width="320" height="240" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect"><effect id="user.a-painter.light" version="3" name="Bundle Light"/></shape></scene>"""
                val bundle = data.newFile("effects.absproj")
                val manifest = """{"format":"absentmotion-package","version":1,"project":"project.xml","media":[]}"""
                ZipOutputStream(bundle.outputStream()).use { zip ->
                    for ((name, contents) in mapOf("manifest.json" to manifest, "project.xml" to scene, "effects/a-painter.light.json" to effect)) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(contents.toByteArray())
                        zip.closeEntry()
                    }
                }
                setContent { DeskApp(app) }
                try {
                    val question = Strings.get(R.string.added_effect_bundle_question, "Bundle Light")
                    val importing = scope.async { app.importFiles(listOf(Uri.fromFile(bundle))) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(question).fetchSemanticsNodes().size == 1 }
                    onNodeWithText(question).assertIsDisplayed()
                    assertFalse(File(app.context.filesDir, "effects-added/a-painter.light.json").exists())
                    onNodeWithText(Strings.get(R.string.community_add)).performClick()
                    waitUntil(timeoutMillis = 10_000) { importing.isCompleted }
                    val report = runBlocking { importing.await().single() }
                    assertEquals(3L, app.engine.effects.value.find("user.a-painter.light")?.addedVersion)
                    val opening = scope.async { app.openNow(report.id) }
                    waitUntil(timeoutMillis = 10_000) { opening.isCompleted }
                    assertTrue(runBlocking { opening.await() } != null)
                    onNodeWithText(question).assertDoesNotExist()
                    onNodeWithText(Strings.get(R.string.added_effect_missing)).assertDoesNotExist()
                } finally {
                    runBlocking { app.closeEditor() }
                    app.engine.close()
                }
            }
        } finally {
            scope.cancel()
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
        }
    }
}
