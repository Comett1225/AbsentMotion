package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PatternMigrationTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun migrate(id: String, vararg props: Pair<String, String>): XmlNode {
        val effect = """<effect id="com.alightcreative.effects.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>"""
        val scene = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""")
        return migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.value(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.number(name: String): Float = value(name)!!.split(',').first().trim().toFloat()

    @Test fun tilesKeepTheLayerSizedTilesAndZoomInsideThem() {
        val e = migrate("tile", "scale" to "5.75", "mirror" to "true")
        assertEquals("absent.tiles", e["id"])
        assertEquals(2f, e.number("sizing"), 1e-6f)
        assertEquals(5.75f, e.number("zoom"), 1e-6f)
        assertEquals(2f, migrate("tile").number("sizing"), 1e-6f)
        assertEquals(3, catalog.find("absent.tiles")!!.params.single { it.id == "sizing" }.options.size)
        assertEquals("0.000000", catalog.find("absent.tiles")!!.params.single { it.id == "sizing" }.default)
    }

    @Test fun stripesKeepAlightsCountWidthAndPhase() {
        val d = migrate("stripes")
        assertEquals(6f, d.number("count"), 1e-6f)
        assertEquals(null, d.value("phase"))
        assertEquals(1f, d.number("style"), 0f)
        val v = migrate("stripes", "amount" to "57.5", "width" to "0.625", "smoothing" to "0.1325", "phase" to "250")
        assertEquals(57.5f, v.number("count"), 1e-4f)
        assertEquals(0.625f, v.number("width"), 1e-6f)
        assertEquals(0.1325f, v.number("smoothing"), 1e-6f)
        assertEquals(250f, v.number("phase"), 1e-4f)
    }

    @Test fun savedStripesAndWipesKeepTheClassicLook() {
        for (key in listOf("stripes", "wipe", "fade", "smoothbevel", "rgbsplit", "sharpen")) {
            val style = catalog.find("absent.$key")!!.params.single { it.id == "style" }
            assertEquals("1.000000", style.default)
            assertEquals("0.000000", style.missingDefault)
            assertEquals(listOf("Classic", "Standard"), style.options)
        }
        val saved = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            <effect id="absent.stripes"><property name="count" value="4" /></effect><property name="size" type="vec2" value="110,82.5" /></shape></scene>""")
        assertEquals(saved.node, migration.migrate(saved).scene.node)
        assertEquals(1f, migrate("wipe2", "angle" to "30").number("style"), 0f)
        assertEquals("20.000000", catalog.find("absent.smoothbevel")!!.params.single { it.id == "blur" }.missingDefault)
    }

    @Test fun checkersUseAlightsCellSizeAndKeepNegativeStretch() {
        assertEquals(10f / 2.475f, migrate("checker2").number("count"), 1e-4f)
        assertEquals(6f / 2.475f, migrate("checker").number("count"), 1e-4f)
        val v = migrate("checker2", "amount" to "57.5", "stretch" to "41,-24", "position" to "40,-25")
        assertEquals(57.5f / 2.475f, v.number("count"), 1e-3f)
        assertEquals("41.000000,-24.000000", v.value("stretch"))
        assertEquals("40.000000,-25.000000", v.value("offset"))
    }

    @Test fun gridsStartAtTheCornerWithAlightsDefaults() {
        for (id in listOf("grid", "grid2")) {
            val d = migrate(id)
            assertEquals(0.1f, d.number("spacing"), 1e-6f)
            assertEquals(0.05f, d.number("width"), 1e-6f)
            assertEquals("#ff2d1ef6", d.value("color"))
            assertEquals(1f, d.number("origin"), 1e-6f)
            assertEquals(1f, d.number("style"), 1e-6f)
            val v = migrate(id, "spacing" to "0.575", "screenSpace" to "true", "smoothing" to "0.05")
            assertEquals(0.575f, v.number("spacing"), 1e-6f)
            assertEquals(0.05f, v.number("smoothing"), 1e-6f)
            assertEquals(2f, v.number("origin"), 1e-6f)
        }
        val origin = catalog.find("absent.grid")!!.params.single { it.id == "origin" }
        assertEquals("0.000000", origin.default)
        assertEquals(listOf("Center", "Layer Corner", "Screen Corner"), origin.options)
    }

    @Test fun tileRotateAndShiftUseAlightsCellSize() {
        for (id in listOf("tilerotate", "tilerotate2", "tileshift", "tileshift2")) {
            assertEquals(id, 6f / 2.475f, migrate(id).number("count"), 1e-4f)
            assertEquals(id, 57.5f / 2.475f, migrate(id, "amount" to "57.5").number("count"), 1e-3f)
            assertEquals(id, 1f, migrate(id).number("edges"), 1e-6f)
            assertEquals(id, "true", migrate(id, "screenSpace" to "true").value("screenSpace"))
        }
        assertEquals("41.000000,-24.000000", migrate("tilerotate2", "stretch" to "41,-24").value("stretch"))
        assertEquals("41.000000,-24.000000", migrate("tileshift2", "stretch" to "41,-24").value("stretch"))
        assertEquals("1.000000,3.250000", migrate("tilerotate", "stretch" to "3.25").value("stretch"))
        assertEquals("3.250000,1.000000", migrate("tileshift", "stretch" to "3.25").value("stretch"))
        for (id in listOf("tileshift", "tileshift2")) {
            assertEquals(id, 1.5f, migrate(id).number("strength"), 1e-5f)
            assertEquals(id, 0.975f, migrate(id, "strength" to "0.325").number("strength"), 1e-5f)
        }
        for (key in listOf("absent.tilerotate", "absent.tileshift", "absent.pixelate")) {
            val edges = catalog.find(key)!!.params.single { it.id == "edges" }
            assertEquals(key, "0.000000", edges.default)
            assertEquals(key, listOf("Mirror", "Clear"), edges.options)
        }
    }

    @Test fun pixelateKeepsAlightsStretchAndClearsOutsideCells() {
        val v = migrate("pixelate2", "size" to "140", "stretch" to "41,-24", "screenSpace" to "true")
        assertEquals("41.000000,-24.000000", v.value("stretch"))
        assertEquals(1f, v.number("edges"), 1e-6f)
        assertEquals("501.000000,1.000000", migrate("pixelate", "stretch" to "500").value("stretch"))
        assertEquals("3.250000,1.000000", migrate("checker", "stretch" to "3.25").value("stretch"))
    }

    @Test fun hexagonTilingShowsThePictureInsideEveryTile() {
        val d = migrate("hextile", "size" to "30", "texCrop" to "1")
        assertEquals(0.6f, d.number("scale"), 1e-5f)
        assertEquals(1f, d.number("fit"), 1e-6f)
        assertEquals(1f, d.number("crop"), 1e-6f)
        val v = migrate("hextile", "size" to "72.5", "stretch" to "3.25", "texCrop" to "1.75", "position" to "40,-25", "screenSpace" to "true", "maskToLayer" to "true")
        assertEquals(1.45f, v.number("scale"), 1e-5f)
        assertEquals("1.000000,3.250000", v.value("stretch"))
        assertEquals(1.75f, v.number("crop"), 1e-6f)
        assertEquals("40.000000,-25.000000", v.value("offset"))
        assertEquals("true", v.value("screenSpace"))
        assertEquals("true", v.value("mask"))
        val fit = catalog.find("absent.hexagontiling")!!.params.single { it.id == "fit" }
        assertEquals("0.000000", fit.default)
        assertEquals(listOf("Full Size", "In Each Tile"), fit.options)
    }

    @Test fun kaleidoscopeTurnsCounterclockwiseAroundAlightsScaledCenter() {
        val v = migrate("kaleidoscope", "rcount" to "10.5", "centerPoint" to "40,-25", "offsetAngle" to "900")
        assertEquals(1f, v.number("direction"), 1e-6f)
        val center = v.value("center")!!.split(',').map { it.trim().toFloat() }
        assertEquals(0.5f + 0.185f * 40f / 220f, center[0], 1e-5f)
        assertEquals(0.5f - 0.185f * 25f / 165f, center[1], 1e-5f)
        val direction = catalog.find("absent.kaleidoscope")!!.params.single { it.id == "direction" }
        assertEquals("0.000000", direction.default)
        assertEquals(listOf("Clockwise", "Counterclockwise"), direction.options)
    }

    @Test fun ribbonSitsOnAlightsSideAndKeepsItsEdgesSharp() {
        val v = migrate("ribbon", "amplitude" to "0.4", "frequency" to "7.25", "offset" to "0.25", "smoothing" to "0.2575", "angle" to "900")
        assertEquals(-0.5f, v.number("offset"), 1e-6f)
        assertEquals(-900f, v.number("angle"), 1e-4f)
        assertEquals(0.02575f, v.number("smoothing"), 1e-6f)
        assertEquals(0.2f, v.number("magnitude"), 1e-6f)
        assertEquals(3.9875f, v.number("frequency"), 1e-5f)
    }

    @Test fun dotsTakeAlightsCellsPerLayerHeightAndRadiusPerCell() {
        for (id in listOf("dots", "dots2")) {
            val v = migrate(id, "scale" to "25.75", "radius" to "0.65")
            assertEquals(id, 13f / 25.75f, v.number("scale"), 1e-5f)
            assertEquals(id, 1.3f, v.number("radius"), 1e-5f)
        }
    }

    @Test fun mosaicFillsAlightsTenPixelCellsWhenTheSizeIsLeftOut() {
        assertEquals(165f / 10f, migrate("mosaic").number("count"), 1e-4f)
        assertEquals(165f / 132.5f, migrate("mosaic", "size" to "132.5").number("count"), 1e-4f)
        assertEquals(1f, migrate("mosaic").number("average"), 1e-6f)
        val average = catalog.find("absent.mosaic")!!.params.single { it.id == "average" }
        assertEquals("0.000000", average.default)
        assertEquals(listOf("Cell", "Corner"), average.options)
    }

    @Test fun blockDissolveKeepsAlightsStretchAndOffsetAndConvertsTheOldId() {
        val v = migrate("checkerdissolve2", "stretch" to "41,-24", "offset" to "40,-25")
        assertEquals("41.000000,-24.000000", v.value("stretch"))
        assertEquals("7.400000,-4.625000", v.value("offset"))
        val old = migrate("checkerdissolve", "amount" to "0.8125", "size" to "132.5", "offset" to "40,-25", "stretch" to "500")
        assertEquals("absent.blockdissolve", old["id"])
        assertEquals(0.8125f, old.number("strength"), 1e-6f)
        assertEquals(132.5f, old.number("blockSize"), 1e-4f)
        assertEquals("7.400000,-4.625000", old.value("offset"))
        assertEquals("500.000000,1.000000", old.value("stretch"))
    }

    @Test fun anAbsentDefaultIsOnlyUsedWhenTheFileLeavesThePropertyOut() {
        val rules = """{"com.alightcreative.effects.mosaic": {"to": "mosaic", "params": {"size": {"to": "count", "power": -1, "per": "height", "absent": 10}}}}"""
        val only = EffectMigration.parse(rules, catalog)
        fun count(effect: String) = only.migrate(Scene.parse("""<scene width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="50,50" /></shape></scene>""")).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }.number("count")
        assertEquals(10f, count("""<effect id="com.alightcreative.effects.mosaic" />"""), 1e-5f)
        assertEquals(4f, count("""<effect id="com.alightcreative.effects.mosaic"><property name="size" value="25" /></effect>"""), 1e-5f)
    }
}
