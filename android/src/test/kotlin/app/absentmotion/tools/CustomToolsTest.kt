package app.absentmotion.tools

import android.net.Uri
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.parseColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val uri: Uri = android.net.TestUri("content://test/clip.mp4")

    private fun video(seconds: Long = 20, name: String = "clip.mp4") =
        PickedMedia(uri, name, 0, CompressSource(true, 1_000_000, 1920, 1080, 30f, seconds * 1_000_000, audio = true, audioAac = true, audioKbps = 128))

    private fun tool(steps: String, inputs: String = "", where: String = "\"home\", \"editor\"", permissions: String = "\"media.read\", \"media.write\"", extra: String = "") = """
        {"id": "test-tool", "name": "Test tool", "version": "1.0.0", "api": 1, "creator": "Someone", "description": "Does a test.",
         "runs": "recipe", "where": [$where], "permissions": [$permissions], $extra
         "inputs": [$inputs], "steps": [$steps]}
    """.trimIndent()

    private fun refused(text: String, part: String) {
        try {
            parseTool(text)
            fail("expected a refusal containing \"$part\"")
        } catch (e: ToolProblem) {
            assertTrue("\"${e.message}\" should contain \"$part\"", e.message!!.contains(part))
        }
    }

    private val examples = listOf(
        """{"id":"beat-cutter","name":"Beat cutter","version":"1.0.0","api":1,"minVersion":52,"creator":"AbsentMotion","licence":"CC BY 4.0",
            "description":"Cuts a video or sound layer on the beats of its music.","icon":"icon.png","runs":"recipe","where":["editor"],
            "permissions":["media.read","project.write"],
            "inputs":[{"id":"clip","type":"layer","label":"Layer to cut"},
              {"id":"every","type":"choice","label":"Cut on","options":[{"label":"Every beat","value":1},{"label":"Every 2 beats","value":2},{"label":"Every 4 beats","value":4}],"default":1}],
            "steps":[{"op":"beats","in":"clip","out":"beats"},{"op":"every","list":"beats","step":"${'$'}every","out":"cuts"},{"op":"split","layer":"clip","at":"cuts"}]}""",
        """{"id":"title-card","name":"Title card","version":"1.0.0","api":1,"minVersion":52,"creator":"AbsentMotion","licence":"CC BY 4.0",
            "description":"Adds a title at the playhead.","icon":"icon.png","runs":"recipe","where":["editor"],"permissions":["project.write"],
            "inputs":[{"id":"title","type":"text","label":"Title","default":"My title","maxLength":80},{"id":"colour","type":"colour","label":"Colour","default":"#ffffff"},
              {"id":"length","type":"number","label":"Length","min":1,"max":20,"step":0.5,"default":3,"unit":"s"},
              {"id":"size","type":"number","label":"Size","min":50,"max":500,"step":10,"default":300,"unit":"%"}],
            "steps":[{"op":"addText","text":"${'$'}title","colour":"${'$'}colour","length":"${'$'}length","out":"card"},
              {"op":"setProperty","layer":"card","property":"scale","value":"${'$'}size"},
              {"op":"addKeyframe","layer":"card","property":"opacity","at":0,"value":0},{"op":"addKeyframe","layer":"card","property":"opacity","at":0.4,"value":100},
              {"op":"addKeyframe","layer":"card","property":"opacity","at":-0.4,"value":100},{"op":"addKeyframe","layer":"card","property":"opacity","at":-0.001,"value":0},
              {"op":"applyEffect","layer":"card","effect":"absent.dropshadow","values":{"opacity":0.5,"blur":16,"x":0,"y":8}}]}""",
        """{"id":"sound-bite","name":"Sound bite","version":"1.0.0","api":1,"minVersion":52,"creator":"AbsentMotion","licence":"CC BY 4.0",
            "description":"Cuts a short part out of a video and keeps only its sound.","icon":"icon.png","runs":"recipe","where":["home","editor"],
            "permissions":["media.read","media.write"],
            "inputs":[{"id":"video","type":"video","label":"Video"},{"id":"start","type":"number","label":"Start at","min":0,"max":3600,"step":0.5,"default":0,"unit":"s"},
              {"id":"length","type":"number","label":"Length","min":1,"max":120,"step":1,"default":10,"unit":"s"},
              {"id":"format","type":"choice","label":"Format","options":[{"label":"M4A","value":"m4a"},{"label":"MP3","value":"mp3"}],"default":1}],
            "steps":[{"op":"trim","in":"video","start":"${'$'}start","length":"${'$'}length","out":"part"},{"op":"extractAudio","in":"part","format":"${'$'}format","out":"sound"}]}""",
    )

    private class FakeHost(private val dir: File, private val uri: Uri, val beatList: List<Double> = emptyList()) : ToolHost {
        val calls = ArrayList<String>()
        var block: CompletableDeferred<Unit>? = null
        private fun made(name: String, mime: String, video: Boolean) = Compressed(File(dir, name).apply { writeText(name) }, mime, video)
        override val layerSeconds = 2
        override suspend fun read(file: Compressed): PickedMedia {
            calls += "read ${file.file.name}"
            return PickedMedia(uri, file.file.name, 0, CompressSource(true, file.file.length(), 1280, 720, 30f, 5_000_000, audio = true))
        }
        override suspend fun trim(media: PickedMedia, range: TrimRange, progress: (Float) -> Unit): Compressed {
            calls += "trim ${media.name} ${range.startUs} ${range.endUs}"
            block?.let { it.complete(Unit); awaitCancellation() }
            progress(1f)
            return made("trimmed.mp4", "video/mp4", true)
        }
        override suspend fun compress(media: PickedMedia, level: CompressLevel, progress: (Float) -> Unit): Compressed {
            calls += "compress $level"
            return made("small.mp4", "video/mp4", true)
        }
        override suspend fun extractAudio(media: PickedMedia, mp3: Boolean, progress: (Float) -> Unit): Compressed {
            calls += "extract ${media.name} mp3=$mp3"
            return made(if (mp3) "sound.mp3" else "sound.m4a", if (mp3) "audio/mpeg" else "audio/mp4", false)
        }
        override suspend fun gif(media: PickedMedia, choice: GifChoice, range: TrimRange, progress: (Float) -> Unit): Compressed {
            calls += "gif ${choice.side} ${choice.fps} ${choice.loop} ${range.startUs} ${range.endUs}"
            return made("clip.gif", "image/gif", false)
        }
        override suspend fun beats(media: PickedMedia): List<Double> { calls += "beats file"; return beatList }
        override suspend fun beats(scene: Scene, layer: Layer): List<Double> { calls += "beats layer ${layer.id}"; return beatList }
        override suspend fun declare(file: Any, kind: ValueKind): MediaDeclaration {
            calls += "declare $kind"
            return MediaDeclaration(XmlNode("media", mapOf("uri" to "file:///kept/clip.mp4", "type" to "video/mp4", "width" to "1280", "height" to "720", "duration" to "4000000")))
        }
        override fun effect(id: String) = if (id == "absent.dropshadow") shadow else null
        override fun preset(id: String): Layer? = if (id == "pop") Layer(XmlNode("text", mapOf("id" to "9", "blending" to "add"), listOf(XmlNode("effect", mapOf("id" to "absent.glow"))))) else null
    }

    private fun scene(): Scene {
        val video = Layer(XmlNode("shape", linkedMapOf("id" to "7", "label" to "Song", "startTime" to "0", "endTime" to "8000", "fillType" to "media",
            "fillVideo" to "file:///song.mp4", "outTime" to "8000", "mediaFillMode" to "fill", "s" to ".rect")))
        return Scene.create("Test", 1080, 1920, 30, 0).add(video)
    }

    @Test fun theExampleToolsAreValid() {
        val tools = examples.map(::parseTool)
        assertEquals(listOf("beat-cutter", "title-card", "sound-bite"), tools.map { it.id })
        assertTrue(tools[0].changesProject)
        assertEquals(setOf("editor"), tools[1].where)
        assertFalse(tools[2].changesProject)
        assertEquals(52, tools[2].minVersion)
        checkEffects(tools[1]) { if (it == "absent.dropshadow") shadow else null }
    }

    @Test fun badToolsAreRefusedWithAClearMessage() {
        refused("not json", "not valid JSON")
        refused(tool("""{"op": "explode", "in": "v"}""", """{"id": "v", "type": "video", "label": "V"}"""), "unknown op \"explode\"")
        refused(tool("""{"op": "trim", "in": "nothing", "start": 0}"""), "names \"nothing\", which is no input")
        refused(tool("""{"op": "trim", "in": "v", "start": "soon"}""", """{"id": "v", "type": "video", "label": "V"}"""), "\"start\" must be a number")
        refused(tool("""{"op": "trim", "in": "v", "start": -1}""", """{"id": "v", "type": "video", "label": "V"}"""), "\"start\" must be from 0")
        refused(tool("""{"op": "trim", "in": "v"}""", """{"id": "v", "type": "video", "label": "V"}"""), "\"start\" is missing")
        refused(tool("""{"op": "trim", "in": "v", "start": 0, "speed": 2}""", """{"id": "v", "type": "video", "label": "V"}"""), "unknown field \"speed\"")
        refused(tool("""{"op": "extractAudio", "in": "t"}""", """{"id": "t", "type": "text", "label": "T"}"""), "needs video")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "film", "label": "V"}"""), "unknown type \"film\"")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "video", "label": "V"}""", permissions = "\"media.read\""), "must also name media.write")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "video", "label": "V"}""", permissions = "\"media.read\", \"media.write\", \"network\""), "unknown \"network\"")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "video", "label": "V"}""", permissions = "\"media.read\", \"media.write\", \"project.write\""), "no step needs it")
        refused(tool("""{"op": "addText", "text": "Hi"}""", permissions = "\"project.write\""), "runs only in the editor")
        refused(tool("""{"op": "every", "list": "b", "step": 2}""", permissions = ""), "no input or earlier")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "video", "label": "V"}""", extra = "\"api\": 2,").replace("\"api\": 1, ", ""), "\"api\" 2 is not supported")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "video", "label": "V"}""", extra = "\"script\": \"x\","), "unknown field \"script\"")
        refused(tool("""{"op": "trim", "in": "v", "start": "${'$'}n"}""", """{"id": "v", "type": "video", "label": "V"}, {"id": "n", "type": "number", "label": "N", "min": -5, "max": 5}"""), "must stay from 0")
        refused(tool("""{"op": "gif", "in": "v", "fps": "${'$'}f"}""", """{"id": "v", "type": "video", "label": "V"}, {"id": "f", "type": "choice", "label": "F", "options": [{"label": "Fast", "value": 60}]}"""), "option value 60 must be from 1 to 30")
        refused(tool("""{"op": "trim", "in": "v", "start": 0, "out": "v"}""", """{"id": "v", "type": "video", "label": "V"}"""), "\"v\" is already used")
        refused(tool(List(33) { """{"op": "trim", "in": "v", "start": 0}""" }.joinToString(), """{"id": "v", "type": "video", "label": "V"}"""), "1 to 32 steps")
        refused(tool("""{"op": "trim", "in": "v", "start": 0}""", """{"id": "v", "type": "video", "label": "V", "notes": "${"x".repeat(70_000)}"}"""), "larger than 64 KB")
        refused(tool("""{"op": "beats", "in": "v", "out": "b"}""", """{"id": "v", "type": "video", "label": "V"}""", permissions = "\"media.read\""), "makes nothing")
        val effects = parseTool(tool("""{"op": "applyEffect", "layer": "l", "effect": "absent.nothing"}""", """{"id": "l", "type": "layer", "label": "L"}""", where = "\"editor\"", permissions = "\"project.write\""))
        try { checkEffects(effects) { null }; fail() } catch (e: ToolProblem) { assertTrue(e.message!!.contains("no effect \"absent.nothing\"")) }
        val setting = parseTool(tool("""{"op": "applyEffect", "layer": "l", "effect": "absent.dropshadow", "values": {"wobble": 1}}""", """{"id": "l", "type": "layer", "label": "L"}""", where = "\"editor\"", permissions = "\"project.write\""))
        try { checkEffects(setting) { shadow }; fail() } catch (e: ToolProblem) { assertTrue(e.message!!.contains("no setting \"wobble\"")) }
    }

    @Test fun mediaStepsRunInOrderAndKeepOnlyTheResult() = runBlocking {
        val work = tmp.newFolder("work")
        val folder = tmp.newFolder("run")
        val host = FakeHost(work, uri)
        val progress = ArrayList<Float>()
        val run = runTool(parseTool(examples[2]), mapOf("video" to video(), "start" to 2.0, "length" to 7.3, "format" to 1), host, null, 0, folder) { progress += it }
        assertEquals(listOf("trim clip.mp4 2000000 9000000", "read trimmed.mp4", "extract trimmed.mp4 mp3=true"), host.calls)
        assertEquals("audio/mpeg", run.result!!.mime)
        assertEquals(folder, run.result!!.file.parentFile)
        assertEquals(listOf("sound.mp3"), folder.list()!!.toList())
        assertTrue(run.edits.isEmpty())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun compressAndGifUseTheirSettings() = runBlocking {
        val host = FakeHost(tmp.newFolder(), uri)
        val gif = runTool(parseTool(tool("""{"op": "gif", "in": "v", "start": 1, "length": 3, "size": 320, "fps": 10, "loop": false}""", """{"id": "v", "type": "video", "label": "V"}""")),
            mapOf("v" to video()), host, null, 0, null) {}
        assertEquals("image/gif", gif.result!!.mime)
        val small = runTool(parseTool(tool("""{"op": "compress", "in": "v", "level": "${'$'}l"}""", """{"id": "v", "type": "video", "label": "V"}, {"id": "l", "type": "choice", "label": "L", "options": [{"label": "A", "value": "low"}, {"label": "B", "value": "high"}]}""")),
            mapOf("v" to video(), "l" to 1), host, null, 0, null) {}
        assertEquals("video/mp4", small.result!!.mime)
        assertEquals(listOf("gif 320 10 false 1000000 4000000", "compress High"), host.calls)
        try {
            runTool(parseTool(tool("""{"op": "trim", "in": "v", "start": 30}""", """{"id": "v", "type": "video", "label": "V"}""")), mapOf("v" to video(20)), host, null, 0, null) {}
            fail()
        } catch (_: ToolProblem) {
        }
    }

    @Test fun beatCutterSplitsTheLayerOnEveryOtherBeatAsOneChange() = runBlocking {
        val start = scene()
        val host = FakeHost(tmp.newFolder(), uri, beatList = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0))
        val run = runTool(parseTool(examples[0]), mapOf("clip" to 7L, "every" to 1), host, start, 0, null) {}
        assertEquals(listOf("beats layer 7"), host.calls)
        assertEquals(1, start.layers.size)
        assertNull(run.result)
        val after = run.edits.fold(start) { s, change -> change(s) }
        assertEquals(listOf(0, 1000, 3000, 5000, 7000), after.layers.map { it.startTime }.sorted())
        assertEquals(8000, after.layers.maxOf { it.endTime })
        assertEquals(7L, after.layers.minByOrNull { it.startTime }!!.id)
    }

    @Test fun titleCardAddsAnAnimatedTextWithAShadow() = runBlocking {
        val start = scene()
        val run = runTool(parseTool(examples[1]), mapOf("title" to "Hello", "colour" to "#ff0000", "length" to 4.0, "size" to 250.0), FakeHost(tmp.newFolder(), uri), start, 15, null) {}
        val after = run.edits.fold(start) { s, change -> change(s) }
        val card = after.layers.single { it.kind == "text" }
        assertEquals(500, card.startTime)
        assertEquals(4499, card.endTime)
        assertEquals("Hello", card.label)
        assertEquals(0xFFFF0000.toInt(), parseColor(card.element(PropRef.FillColor)!!["value"]))
        assertEquals(2.5f, card.scale[0], 1e-4f)
        assertEquals(2.5f, card.scale[1], 1e-4f)
        val opacity = card.element(PropRef.Opacity)!!
        assertTrue(opacity.isKeyframed)
        assertEquals(listOf("0.000000", "1.000000", "1.000000", "0.000000"), opacity.keyframes.map { it.v })
        assertEquals(0f, opacity.keyframes.first().t, 1e-4f)
        assertEquals(1f, opacity.keyframes.last().t, 1e-3f)
        val effect = card.effects.single()
        assertEquals("absent.dropshadow", effect["id"])
        assertEquals("0.500000", effect.children.first { it["name"] == "opacity" }["value"])
    }

    @Test fun layerStepsChangeOnlyTheChosenLayer() = runBlocking {
        val start = scene()
        val host = FakeHost(tmp.newFolder(), uri)
        val text = tool("""{"op": "applyPreset", "layer": "l", "preset": "p"},
            {"op": "setProperty", "layer": "l", "property": "position", "value": [100, "${'$'}y"]},
            {"op": "setProperty", "layer": "l", "property": "rotation", "value": 45},
            {"op": "setProperty", "layer": "l", "property": "opacity", "value": 40},
            {"op": "speed", "layer": "l", "value": 2},
            {"op": "addMedia", "in": "v", "out": "added"},
            {"op": "split", "layer": "added", "at": 1}""",
            """{"id": "l", "type": "layer", "label": "L"}, {"id": "p", "type": "preset", "label": "P"}, {"id": "v", "type": "video", "label": "V"},
               {"id": "y", "type": "number", "label": "Y", "min": 0, "max": 2000, "default": 300}""",
            where = "\"editor\"", permissions = "\"media.read\", \"project.write\"")
        val run = runTool(parseTool(text), mapOf("l" to 7L, "p" to "pop", "v" to video()), host, start, 0, null) {}
        assertEquals(listOf("declare Video"), host.calls)
        val after = run.edits.fold(start) { s, change -> change(s) }
        val layer = after.layer(7)!!
        assertEquals(100f, layer.location[0], 1e-4f)
        assertEquals(300f, layer.location[1], 1e-4f)
        assertEquals(45f, layer.rotation, 1e-4f)
        assertEquals(0.4f, layer.opacity, 1e-4f)
        assertTrue(layer.endTime in 3990..4010)
        assertEquals("add", layer.node["blending"])
        assertEquals("pop", layer.node["presetId"])
        assertEquals(3, after.layers.size)
        assertNotNull(after.media("file:///kept/clip.mp4"))
    }

    @Test fun cancellingStopsTheRunAndChangesNothing() = runBlocking {
        val work = tmp.newFolder()
        val host = FakeHost(work, uri).apply { block = CompletableDeferred() }
        val job = async(Dispatchers.Default) { runTool(parseTool(examples[2]), mapOf("video" to video()), host, null, 0, null) {} }
        host.block!!.await()
        job.cancel()
        try {
            job.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("trim clip.mp4 0 10000000"), host.calls)
        assertTrue(work.list()!!.isEmpty())
    }

    @Test fun installedToolsAreReadBackAndRemovedWithoutFollowingLinks() {
        val root = tmp.newFolder("tools")
        val json = tmp.newFile("tool.json").apply { writeText(examples[2]) }
        val icon = tmp.newFile("icon.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = tmp.newFile("keep.txt").apply { writeText("keep") }
        val tool = parseTool(json.readText())
        java.nio.file.Files.createSymbolicLink(File(root, "sound-bite").toPath(), outside.toPath())
        assertEquals(tool.id, InstalledTools.install(root, "sound-bite", json, icon).id)
        assertTrue(outside.isFile)
        assertEquals(examples[2], File(root, "sound-bite/tool.json").readText())
        assertEquals(listOf("sound-bite"), InstalledTools.list(root).map { it.tool.id })
        InstalledTools.remove(root, "sound-bite")
        assertFalse(File(root, "sound-bite").exists())
        assertEquals("keep", outside.readText())
    }

    private companion object {
        val shadow = EffectDefinition("absent.dropshadow", "dropshadow", "Drop Shadow", "stylize", "", listOf(
            EffectParam("color", "Color", "color", "#ff000000"), EffectParam("opacity", "Opacity", "float", "0.600000", 0f, 1f),
            EffectParam("blur", "Blur", "float", "12.000000", 0f, 200f), EffectParam("x", "X", "float", "12.000000", -500f, 500f),
            EffectParam("y", "Y", "float", "12.000000", -500f, 500f),
        ), native = true)
    }
}
