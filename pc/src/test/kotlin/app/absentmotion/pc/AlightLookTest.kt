package app.absentmotion.pc

import app.absentmotion.compat.EffectMigration
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.pc.engine.DesktopEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

class AlightLookTest {
    @Test fun importedTilesMatchAlight() {
        val stillsPath = System.getenv("ALIGHT_STILLS")
        val imagePath = System.getenv("ALIGHT_TEST_IMAGE")
        assumeTrue("Set ALIGHT_STILLS and ALIGHT_TEST_IMAGE", !stillsPath.isNullOrBlank() && !imagePath.isNullOrBlank())
        val root = File(requireNotNull(System.getenv("ALIGHT_REPO")) { "Set ALIGHT_REPO" }).canonicalFile
        val out = File(requireNotNull(System.getenv("ALIGHT_LOOK_OUT")) { "Set ALIGHT_LOOK_OUT outside the repository" }).canonicalFile
        require(!out.toPath().startsWith(root.toPath()))
        out.mkdirs()
        val pack = root.resolve("shared/assets/effects")
        val catalog = EffectCatalog.parse { pack.resolve(it).inputStream() }
        val migration = EffectMigration.parse(pack.resolve("foreign.json").readText(), catalog)
        val same = JSONArray(root.resolve("tools/alight_compat.json").readText()).let { entries ->
            (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.optString("measured") == "same" }.map { it.getString("id") }.toSet()
        }
        val targets = setOf("com.alightcreative.glow", "com.alightcreative.effects.mirror", "com.alightcreative.effects.chromaticzoomblur", "com.alightcreative.effects.mattechoke", "com.alightcreative.mattechoke", "com.alightcreative.effects.lumastamper")
        val sheets = System.getenv("ALIGHT_SHEETS")?.takeIf { it.isNotBlank() }?.let { wanted ->
            if (wanted == "all") File(stillsPath!!).listFiles()!!.filter { it.resolve("scene.xml").isFile && it.resolve("00000.png").isFile }.map { it.name }.sorted()
            else wanted.split(',').map(String::trim)
        } ?: listOf("effectsv-17", "effectsv-09", "effectsv-18", "effectsv-03", "effectsv-08", "effects-17", "effects1", "effects-09", "effects-18", "effects-03", "effects-08")
        val explicitDefaults = mapOf(
            "com.alightcreative.glow" to mapOf("radius" to "100", "alpha" to "0.75", "hardness" to "0.5", "color" to "#ffffffaa", "blendMode" to "2"),
            "com.alightcreative.effects.lumastamper" to mapOf("color" to "#ffff5566", "clipLow" to "0", "clipHigh" to "0", "gamma" to "1", "invert" to "false"),
            "com.alightcreative.effects.chromaticzoomblur" to mapOf("centerPoint" to "0,0", "strength" to "0.0525", "adaptive" to "true"),
        )
        fun explicit(node: XmlNode): XmlNode {
            val defaults = explicitDefaults[node["id"]]
            return if (node.name == "effect" && node.children.isEmpty() && defaults != null) node.copy(children = defaults.map { (name, value) ->
                XmlNode("property", mapOf("name" to name, "value" to value))
            }) else node.copy(children = node.children.map(::explicit))
        }
        val scores = JSONArray()
        val failures = mutableListOf<String>()
        fun picture(node: XmlNode): XmlNode = node.copy(attrs = node.attrs.mapValues { (key, value) ->
            if (key in setOf("fillImage", "uri") && value.startsWith("content://")) File(imagePath!!).toURI().toString() else value
        }, children = node.children.map(::picture)).let { mapped ->
            if (mapped.name != "location" || mapped["value"] == null) mapped else {
                val components = mapped["value"]!!.split(',').toMutableList()
                components[1] = (components[1].toDouble() - 0.5).toString()
                mapped.with("value", components.joinToString(","))
            }
        }
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            val errors = engine.loadErrors.filterKeys { it in setOf("glow", "mirror", "chromaticzoomblur", "mattechoker", "lumastamp") }
            assertTrue(errors.toString(), errors.isEmpty())
            for (sheet in sheets) runCatching {
                val dir = File(stillsPath!!, sheet)
                val original = Scene.parse(dir.resolve("scene.xml").readText())
                if (original.node["width"] != "1080" || original.node["height"] != "1080") return@runCatching
                val dropped = System.getenv("ALIGHT_DROP").orEmpty().split(',').filter { it.startsWith("$sheet/") }.map { it.substringAfter('/') }.toSet()
                val kept = original.node.copy(children = original.node.children.filter { it.name != "shape" || it["label"] !in dropped })
                val imported = migration.migrate(Scene(picture(kept))).scene
                val explicitScene = migration.migrate(Scene(picture(explicit(kept)))).scene
                assertEquals("Empty and explicit defaults: $sheet", imported.node.toXml(), explicitScene.node.toXml())
                engine.setScene(imported)
                val frames = dir.resolve("frames.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }?.let { json -> json.keySet().sorted().associateWith { json.getInt(it) } } ?: mapOf("00000.png" to 0)
                val fps = original.node["fps"]?.toDoubleOrNull() ?: 60.0
                for ((file, frame) in frames) {
                    val time = (frame * 1000.0 / fps).roundToInt()
                    val ours = engine.render(time, 1080, 1080)
                    val alight = ImageIO.read(dir.resolve(file))
                    require(alight.width == 1080 && alight.height >= 1116)
                    if (System.getenv("ALIGHT_LOOK_FULL") == "1") ImageIO.write(ours, "png", out.resolve("$sheet-full${if (frame == 0) "" else "-$frame"}.png"))
                    for (tile in original.node.children.filter { it.name == "shape" && it["label"] !in setOf("base", "clock") && time in (it["startTime"]?.toIntOrNull() ?: 0)..(it["endTime"]?.toIntOrNull() ?: Int.MAX_VALUE) }) {
                        val label = tile["label"].orEmpty()
                        val effect = tile.child("effect")?.get("id").orEmpty()
                        val location = tile.child("transform")?.child("location")?.get("value")?.split(',')?.map(String::toDouble) ?: continue
                        val size = tile.children.firstOrNull { it.name == "property" && it["name"] == "size" }?.get("value")?.split(',')?.map(String::toDouble) ?: continue
                        val x0 = (location[0] - size[0] - 15).toInt().coerceAtLeast(0)
                        val x1 = (location[0] + size[0] + 15).toInt().coerceAtMost(1080)
                        val y0 = (location[1] - size[1] - 15).roundToInt().coerceAtLeast(0)
                        val y1 = (location[1] + size[1] + 15).roundToInt().coerceAtMost(1080)
                        val w = x1 - x0
                        val h = y1 - y0
                        val pair = BufferedImage(w * 2, h, BufferedImage.TYPE_INT_RGB)
                        var difference = 0L
                        var interiorDifference = 0L
                        for (y in 0 until h) for (x in 0 until w) {
                            val a = ours.getRGB(x0 + x, y0 + y)
                            val b = alight.getRGB(x0 + x, y0 + y + 36)
                            pair.setRGB(x, y, a)
                            pair.setRGB(x + w, y, b)
                            for (shift in listOf(0, 8, 16)) {
                                val delta = abs((a shr shift and 255) - (b shr shift and 255))
                                difference += delta
                                if (x in 15 until w - 15 && y in 15 until h - 15) interiorDifference += delta
                            }
                        }
                        val error = difference.toDouble() / (w * h * 3)
                        val interiorError = interiorDifference.toDouble() / ((w - 30) * (h - 30) * 3)
                        val crowdedGlow = sheet == "effects-17" && effect == "com.alightcreative.glow"
                        val acceptedLumaInterior = sheet == "effects-08" && effect == "com.alightcreative.effects.lumastamper"
                        val acceptanceError = if (acceptedLumaInterior) interiorError else error
                        val control = effect.substringAfterLast('.') in same && effect !in targets
                        scores.put(JSONObject().put("sheet", sheet).put("frame", frame).put("label", label).put("effect", effect).put("error", error).put("interiorError", interiorError).put("control", control)
                            .put("acceptanceRegion", if (crowdedGlow) "clean-frames" else if (acceptedLumaInterior) "interior" else "box")
                            .put("acceptanceError", if (crowdedGlow) JSONObject.NULL else acceptanceError))
                        ImageIO.write(pair, "png", out.resolve("$sheet-${label.replace(Regex("[^a-zA-Z0-9_-]"), "_")}${if (frame == 0) "" else "-$frame"}.png"))
                        println("LOOK $sheet $frame $label $effect $error${if (control) " control" else ""}")
                        if (!crowdedGlow && (effect in targets || control) && acceptanceError > 6.5) failures += "$sheet/$label: $acceptanceError"
                    }
                }
            }.onFailure {
                scores.put(JSONObject().put("sheet", sheet).put("crash", it.toString()))
                failures += "$sheet: $it"
            }
        }
        out.resolve("look.json").writeText(scores.toString(2))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    @Test fun importedCleanGlowFramesMatchAlight() {
        val capturesPath = System.getenv("ALIGHT_GLOW_CAPTURES")
        assumeTrue("Set ALIGHT_GLOW_CAPTURES", !capturesPath.isNullOrBlank())
        val root = File(requireNotNull(System.getenv("ALIGHT_REPO")) { "Set ALIGHT_REPO" }).canonicalFile
        val out = File(requireNotNull(System.getenv("ALIGHT_LOOK_OUT")) { "Set ALIGHT_LOOK_OUT outside the repository" }).canonicalFile
        require(!out.toPath().startsWith(root.toPath()))
        out.mkdirs()
        val captures = File(capturesPath!!)
        val pack = root.resolve("shared/assets/effects")
        val migration = EffectMigration.parse(pack.resolve("foreign.json").readText(), EffectCatalog.parse { pack.resolve(it).inputStream() })
        val scores = JSONArray()
        val failures = mutableListOf<String>()
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            assertTrue(engine.loadErrors["glow"].orEmpty(), "glow" !in engine.loadErrors)
            for (folder in listOf("glowA", "glowB", "glowC")) {
                val project = if (folder == "glowC") "glow_full.xml" else "glowA.xml"
                val original = Scene.parse(captures.resolve(project).readText()).node
                val circle = original.children.single { it.name == "shape" && it["label"] == "Circle 1" }
                fun variant(node: XmlNode): XmlNode {
                    if (folder == "glowB" && node.name == "kf" && node["v"] == "60.000000") return node.with("v", "400.000000")
                    return node.copy(children = node.children.map(::variant))
                }
                val background = if (folder == "glowC") "#00000000" else "#ff000000"
                val scene = Scene(original.with("bgcolor", background).copy(children = listOf(variant(circle))))
                engine.setScene(migration.migrate(scene).scene)
                val frames = captures.resolve(folder).listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name.substringBefore('_').toInt() }
                assertEquals(61, frames.size)
                for ((frame, file) in frames.withIndex()) {
                    assertEquals(frame + 1, file.name.substringBefore('_').toInt())
                    val time = (frame * 1000.0 / 30).roundToInt()
                    val progress = ((time - 16.0) / 2000).coerceIn(0.0, 1.0)
                    val radius = when (folder) { "glowA" -> progress * 60; "glowB" -> progress * 400; else -> 100.0 }
                    val hardness = if (folder == "glowC") progress else 0.5
                    val rendered = engine.render(time, 720, 720)
                    val ours = BufferedImage(720, 720, BufferedImage.TYPE_INT_RGB)
                    ours.createGraphics().let { graphics -> graphics.drawImage(rendered, 0, 0, null); graphics.dispose() }
                    val reference = ImageIO.read(file)
                    assertEquals(720, reference.width)
                    assertEquals(720, reference.height)
                    val pair = BufferedImage(1440, 720, BufferedImage.TYPE_INT_RGB)
                    var total = 0L
                    var ring = 0L
                    var ringPixels = 0
                    for (y in 0 until 720) for (x in 0 until 720) {
                        val a = ours.getRGB(x, y)
                        val b = reference.getRGB(x, y)
                        val distance = kotlin.math.hypot(x + 0.5 - 360, y + 0.5 - 360)
                        val inRing = distance > 100 && distance <= 103 + radius * 0.7
                        if (inRing) ringPixels++
                        for (shift in listOf(0, 8, 16)) {
                            val difference = abs((a shr shift and 255) - (b shr shift and 255))
                            total += difference
                            if (inRing) ring += difference
                        }
                        pair.setRGB(x, y, a)
                        pair.setRGB(x + 720, y, b)
                    }
                    val error = total.toDouble() / (720 * 720 * 3)
                    val ringError = ring.toDouble() / (ringPixels * 3)
                    scores.put(JSONObject().put("folder", folder).put("project", project).put("layer", "Circle 1")
                        .put("frame", frame).put("file", file.name).put("timeMs", time).put("radius", radius).put("hardness", hardness)
                        .put("error", error).put("ringError", ringError).put("ringPixels", ringPixels)
                        .put("blendMode", if (folder == "glowC") 1 else 2).put("background", background).put("radiusKeyframeEnd", if (folder == "glowB") 400 else if (folder == "glowA") 60 else JSONObject.NULL)
                        .put("ringInner", 100).put("ringOuter", 103 + radius * 0.7))
                    if (frame % 10 == 0) ImageIO.write(pair, "png", out.resolve("$folder-$frame.png"))
                    println("GLOW $folder $frame $time $radius $hardness $error $ringError")
                    if (error > 6.5 || ringError > 6.5) failures += "$folder/$frame: $error, ring $ringError"
                }
            }
        }
        out.resolve("glow.json").writeText(scores.toString(2))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

}
