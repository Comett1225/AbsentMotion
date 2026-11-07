package app.absentmotion.compat

import app.absentmotion.library.ImportFailed
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.ProjectStore
import app.absentmotion.R
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlaImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun document(layers: String, extra: String = "", attrs: String = """width="400" height="300" frameRate="24" backgroundColor="#336699"""") =
        """<DOMDocument xmlns="http://ns.adobe.com/xfl/2008/" $attrs>$extra<timelines><DOMTimeline name="Scene 1"><layers>$layers</layers></DOMTimeline></timelines></DOMDocument>"""

    private fun layer(name: String, frames: String, attrs: String = "") = """<DOMLayer name="$name" $attrs><frames>$frames</frames></DOMLayer>"""

    private val square = """<DOMShape><fills><FillStyle index="1"><SolidColor color="#FF0000" alpha="0.5"/></FillStyle></fills>
        <edges><Edge fillStyle1="1" edges="!0 0|2000 0|2000 2000|0 2000|0 0"/></edges></DOMShape>"""

    private fun symbol(name: String, layers: String, type: String = "") =
        """<DOMSymbolItem xmlns="http://ns.adobe.com/xfl/2008/" name="$name" $type><timeline><DOMTimeline name="$name"><layers>$layers</layers></DOMTimeline></timeline></DOMSymbolItem>"""

    private fun read(doc: String, vararg library: Pair<String, String>) = readFla(mapOf("DOMDocument.xml" to doc) + library.map { "LIBRARY/${it.first}" to it.second })

    private fun layers(node: XmlNode): List<XmlNode> = node.children.filter { it.name in setOf("shape", "embedScene", "audio") }

    private fun value(layer: XmlNode, name: String): XmlNode? = layer.child("transform")?.child(name)

    @Test fun readsTheStageAndASolidShape() {
        val fla = read(document(layer("Box", """<DOMFrame index="0" duration="12"><elements>$square</elements></DOMFrame>""", """autoNamed="false"""")))
        val root = fla.root
        assertEquals("400", root["width"])
        assertEquals("300", root["height"])
        assertEquals("24", root["fps"])
        assertEquals("#ff336699", root["bgcolor"])
        assertEquals("499", root["totalTime"])
        val shape = layers(root).single()
        assertEquals("Box", shape["label"])
        assertEquals("color", shape["fillType"])
        assertEquals("#80ff0000", shape.child("fillColor")!!["value"])
        assertEquals("50.000000,50.000000,0.000000", value(shape, "location")!!["value"])
        assertEquals("M -50 -50 L -50 50 L 50 50 L 50 -50 L -50 -50 Z", shape.child("path")!!["d"])
        assertEquals("0", shape["startTime"])
        assertEquals("499", shape["endTime"])
        assertEquals(1, fla.counts[FlaPart.Shapes])
    }

    @Test fun joinsEdgesOfOneFillIntoClosedLoopsAndKeepsHoles() {
        val holed = """<DOMShape><fills><FillStyle index="1"><SolidColor/></FillStyle></fills><edges>
            <Edge fillStyle0="1" edges="!0 0|200 0"/><Edge fillStyle0="1" edges="!200 0|200 200|0 200"/><Edge fillStyle0="1" edges="!0 200|0 0"/>
            <Edge fillStyle1="1" edges="!50 50|150 50|150 150|50 150|50 50"/></edges></DOMShape>"""
        val shape = layers(read(document(layer("L", """<DOMFrame index="0"><elements>$holed</elements></DOMFrame>"""))).root).single()
        val d = shape.child("path")!!
        assertEquals("evenodd", d["fillRule"])
        assertEquals(2, d["d"]!!.count { it == 'Z' })
        assertEquals(2, d["d"]!!.count { it == 'M' })
    }

    @Test fun decodesQuadraticCurvesAndHexNumbers() {
        val curved = """<DOMShape><strokes><StrokeStyle index="1"><SolidStroke weight="4" caps="none" joints="bevel"><fill><SolidColor color="#00FF00"/></fill></SolidStroke></StrokeStyle></strokes>
            <edges><Edge strokeStyle="1" edges="!0 0[#64.80 0 400 400S2"/></edges></DOMShape>"""
        val shape = layers(read(document(layer("L", """<DOMFrame index="0"><elements>$curved</elements></DOMFrame>"""))).root).single()
        assertEquals("none", shape["fillType"])
        assertEquals("M -10 -10 Q -4.975 -10 10 10", shape.child("path")!!["d"])
        val stroke = shape.child("path-stroke")!!
        assertEquals("butt", stroke["cap"])
        assertEquals("bevel", stroke["join"])
        assertEquals("#ff00ff00", stroke.child("color")!!["value"])
        assertEquals("4.000000", stroke.child("size")!!["value"])
    }

    @Test fun mapsTwoColourGradientsToTheShapeBounds() {
        val grad = """<DOMShape><fills><FillStyle index="1"><LinearGradient><matrix><Matrix a="0.06103515625" tx="50" ty="50"/></matrix>
            <GradientEntry color="#000000" ratio="0"/><GradientEntry color="#FFFFFF" ratio="1"/></LinearGradient></FillStyle></fills>
            <edges><Edge fillStyle1="1" edges="!0 0|2000 0|2000 2000|0 2000|0 0"/></edges></DOMShape>"""
        val shape = layers(read(document(layer("L", """<DOMFrame index="0"><elements>$grad</elements></DOMFrame>"""))).root).single()
        val g = shape.child("gradient")!!
        assertEquals("gradient", shape["fillType"])
        assertEquals("linear", g["type"])
        assertEquals("#ff000000", g["startColor"])
        assertEquals("#ffffffff", g["endColor"])
        assertEquals("0.000000,0.500000", g["start"])
        assertEquals("1.000000,0.500000", g["end"])
    }

    @Test fun turnsAClassicTweenIntoEasedKeyframes() {
        val box = symbol("Box", layer("a", """<DOMFrame index="0"><elements>$square</elements></DOMFrame>"""))
        val frames = """<DOMFrame index="0" duration="10" tweenType="motion" acceleration="-100"><elements>
              <DOMSymbolInstance libraryItemName="Box" symbolType="graphic" loop="loop"><matrix><Matrix tx="10" ty="20"/></matrix>
              <transformationPoint><Point x="50" y="50"/></transformationPoint></DOMSymbolInstance></elements></DOMFrame>
            <DOMFrame index="10" duration="5"><elements>
              <DOMSymbolInstance libraryItemName="Box" symbolType="graphic" loop="loop"><matrix><Matrix a="0" b="2" c="-2" d="0" tx="300" ty="20"/></matrix>
              <transformationPoint><Point x="50" y="50"/></transformationPoint><color><Color alphaMultiplier="0.25"/></color></DOMSymbolInstance></elements></DOMFrame>"""
        val fla = read(document(layer("Move", frames)), "Box.xml" to box)
        val group = layers(fla.root).single()
        assertEquals("embedScene", group.name)
        assertEquals("0", group["startTime"])
        assertEquals("624", group["endTime"])
        val location = value(group, "location")!!.children
        assertEquals(listOf("60.000000,70.000000,0.000000", "200.000000,120.000000,0.000000"), location.map { it["v"] })
        assertEquals("cubicBezier 0.3333 0 0.6667 0.3333", location[1]["e"])
        assertEquals("0.666667", location[1]["t"])
        assertEquals(listOf("0.000000", "90.000000"), value(group, "rotation")!!.children.map { it["v"] })
        assertEquals("2.000000,2.000000", value(group, "scale")!!.children[1]["v"])
        assertEquals("0.250000", value(group, "opacity")!!.children[1]["v"])
        assertNull(value(group, "pivot"))
        val inner = group.child("scene")!!
        assertEquals("102", inner["width"])
        assertEquals("freeze", inner["retime"])
        assertEquals(1, fla.counts[FlaPart.Tweens])
        assertEquals(1, fla.counts[FlaPart.Symbols])
    }

    @Test fun holdsUntweenedKeyframesAndSplitsCustomEases() {
        val box = symbol("Box", layer("a", """<DOMFrame index="0"><elements>$square</elements></DOMFrame>"""))
        fun inst(x: Int) = """<elements><DOMSymbolInstance libraryItemName="Box"><matrix><Matrix tx="$x"/></matrix></DOMSymbolInstance></elements>"""
        val frames = """<DOMFrame index="0" duration="4">${inst(0)}</DOMFrame>
            <DOMFrame index="4" duration="4" tweenType="motion" hasCustomEase="true"><tweens><CustomEase target="all">
              <Point/><Point x="0.1" y="0.2"/><Point x="0.3" y="0.4"/><Point x="0.5" y="0.5"/><Point x="0.7" y="0.6"/><Point x="0.9" y="0.8"/><Point x="1" y="1"/>
            </CustomEase></tweens>${inst(100)}</DOMFrame>
            <DOMFrame index="8">${inst(200)}</DOMFrame>"""
        val group = layers(read(document(layer("L", frames)), "Box.xml" to box).root).single()
        val keys = value(group, "location")!!.children
        assertEquals(listOf("0.000000", "0.000000", "100.000000", "150.000000", "200.000000"), keys.map { it["v"]!!.substringBefore(',') })
        assertNull(keys[1]["e"])
        assertNull(keys[2]["e"])
        assertEquals("cubicBezier 0.2 0.4 0.6 0.8", keys[3]["e"])
        assertEquals("cubicBezier 0.4 0.2 0.8 0.6", keys[4]["e"])
    }

    @Test fun mapsTintAndBrightnessToTheTintEffect() {
        val box = symbol("Box", layer("a", """<DOMFrame index="0"><elements>$square</elements></DOMFrame>"""))
        fun inst(color: String) = """<elements><DOMSymbolInstance libraryItemName="Box"><color><Color $color/></color></DOMSymbolInstance></elements>"""
        val frames = """<DOMFrame index="0" duration="4" tweenType="motion">${inst("""tintMultiplier="0.5" tintColor="#FF0000"""")}</DOMFrame>
            <DOMFrame index="4">${inst("""brightness="1"""")}</DOMFrame>"""
        val group = layers(read(document(layer("L", frames)), "Box.xml" to box).root).single()
        val tint = group.children.single { it.name == "effect" }
        assertEquals("absent.tint", tint["id"])
        val props = tint.children.associateBy { it["name"] }
        assertEquals(listOf("0.500000", "1.000000"), props.getValue("amount").children.map { it["v"] })
        assertEquals(listOf("#ffff0000", "#ffffffff"), props.getValue("shadowColor").children.map { it["v"] })
        assertEquals(props.getValue("shadowColor"), props.getValue("highlightColor").copy(attrs = props.getValue("shadowColor").attrs))
    }

    @Test fun groupsMaskedLayersUnderTheirMask() {
        val frames = """<DOMFrame index="0"><elements>$square</elements></DOMFrame>"""
        val doc = document(layer("Mask", frames, """layerType="mask"""") + layer("Art", frames, """parentLayerIndex="0"""") + layer("Back", frames))
        val fla = read(doc)
        val top = layers(fla.root)
        assertEquals(2, top.size)
        assertEquals("shape", top[0].name)
        val masked = top[1]
        assertEquals("embedScene", masked.name)
        val inside = layers(masked.child("scene")!!)
        assertEquals("shape", inside[0].name)
        assertEquals("mask", inside[1]["blending"])
        assertEquals(1, fla.counts[FlaPart.Masks])
    }

    @Test fun reportsWhatItCannotImport() {
        val frames = """<DOMFrame index="0" duration="3" tweenType="shape"><Actionscript><script>stop();</script></Actionscript><elements>
            <DOMStaticText><textRuns/></DOMStaticText><DOMVideoInstance/></elements></DOMFrame>"""
        val fla = read(document(layer("L", frames) + layer("G", "", """layerType="guide"""")), "Spare.xml" to symbol("Spare", ""))
        assertEquals(1, fla.counts[FlaPart.Text])
        assertEquals(1, fla.counts[FlaPart.Scripts])
        assertEquals(1, fla.counts[FlaPart.ShapeTweens])
        assertEquals(1, fla.counts[FlaPart.Videos])
        assertEquals(1, fla.counts[FlaPart.Unused])
        assertTrue(layers(fla.root).isEmpty())
    }

    private fun expanded(node: XmlNode): Int = layers(node).sumOf { 1 + (it.child("scene")?.let(::expanded) ?: 0) }

    private fun nested(levels: Int, copies: Int): List<Pair<String, String>> = (0..levels).map { k ->
        val body = if (k == 0) square else (1..copies).joinToString("") { """<DOMSymbolInstance libraryItemName="S${k - 1}" symbolType="graphic"/>""" }
        "S$k.xml" to symbol("S$k", layer("a", """<DOMFrame index="0"><elements>$body</elements></DOMFrame>"""), """symbolType="graphic"""")
    }

    @Test fun countsEveryCopyOfANestedSymbolAgainstTheLayerLimit() {
        val library = nested(4, 10)
        val stage = layer("L", """<DOMFrame index="0"><elements>${(1..10).joinToString("") { """<DOMSymbolInstance libraryItemName="S4" symbolType="graphic"/>""" }}</elements></DOMFrame>""")
        val fla = readFla(mapOf("DOMDocument.xml" to document(stage)) + library.map { "LIBRARY/${it.first}" to it.second }, maxLayers = 1000)
        assertTrue((fla.counts[FlaPart.Overflow] ?: 0) > 0)
        assertTrue(expanded(fla.root) <= 1000)
    }

    @Test fun stopsAtDeeplyNestedSymbols() {
        val library = nested(200, 1)
        val stage = layer("L", """<DOMFrame index="0"><elements><DOMSymbolInstance libraryItemName="S200" symbolType="graphic"/></elements></DOMFrame>""")
        val fla = readFla(mapOf("DOMDocument.xml" to document(stage)) + library.map { "LIBRARY/${it.first}" to it.second })
        assertTrue((fla.counts[FlaPart.Overflow] ?: 0) > 0)
        assertEquals("scene", XmlNode.parse(fla.root.toXml()).name)
    }

    @Test fun playsSingleFrameGraphicsFromTheirFrame() {
        val two = symbol("Two", layer("a", """<DOMFrame index="0"><elements>$square</elements></DOMFrame><DOMFrame index="1"><elements/></DOMFrame>"""), """symbolType="graphic"""")
        val frames = """<DOMFrame index="0" duration="6"><elements><DOMSymbolInstance libraryItemName="Two" symbolType="graphic" loop="single frame" firstFrame="1"/></elements></DOMFrame>"""
        val group = layers(read(document(layer("L", frames)), "Two.xml" to two).root).single()
        val inner = group.child("scene")!!
        assertEquals("freeze", inner["retime"])
        assertTrue(layers(inner).isEmpty())
    }

    @Test fun decodesLosslessPicturesIntoPng() {
        val pixels = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0, 0x81.toByte(), 0, 0x40, 0)
        val packed = ByteArrayOutputStream().also { out -> val d = Deflater(); d.setInput(pixels); d.finish(); val b = ByteArray(64); while (!d.finished()) out.write(b, 0, d.deflate(b)) }.toByteArray()
        val head = byteArrayOf(3, 5, 8, 0, 2, 0, 1, 0) + ByteArray(16) + byteArrayOf(1, 1, packed.size.toByte(), 0) + packed + byteArrayOf(0, 0)
        val (png, ext) = flaPicture(head)!!
        assertEquals("png", ext)
        assertEquals(0x89.toByte(), png[0])
        val (jpeg, kind) = flaPicture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))!!
        assertEquals("jpg", kind)
        assertEquals(4, jpeg.size)
        assertNull(flaPicture(byteArrayOf(3, 3, 0, 2, 0, 2, 0, 2) + ByteArray(30)))
        assertNotNull(flaSound(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)))
        assertNull(flaSound(byteArrayOf(1, 2, 3, 4)))
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun importsAPackedFlaWithItsPictures() {
        val files = tmp.newFolder("files")
        val store = ProjectStore(files)
        val media = """<media><DOMBitmapItem name="dot.png" href="dot.png" bitmapDataHRef="M 1 1.dat" frameRight="40" frameBottom="20"/></media>"""
        val frames = """<DOMFrame index="0" duration="2"><elements><DOMBitmapInstance libraryItemName="dot.png"><matrix><Matrix tx="10" ty="10"/></matrix></DOMBitmapInstance></elements></DOMFrame>"""
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val bytes = zip(listOf("mimetype" to "application/vnd.adobe.xfl".toByteArray(), "anim/Walk.xfl" to "PROXY-CS5".toByteArray(),
            "anim/DOMDocument.xml" to document(layer("Pic", frames), media).toByteArray(),
            "anim/bin/M 1 1.dat" to jpeg))
        val report = Importer(store, access = MediaAccess.forPhone(files)).import { ByteArrayInputStream(bytes) }.single()
        assertTrue(!report.template)
        assertEquals(R.string.fla_report_imported, report.sections.first().first.id)
        val scene = store.load(report.id)!!
        assertEquals("Walk", scene.title)
        val picture = scene.node.children.single { it.name == "shape" }
        assertEquals(".rect", picture["s"])
        assertEquals("1.000000,0.500000", picture.children.single { it.name == "property" }["value"])
        val file = File(java.net.URI(picture["fillImage"]!!))
        assertTrue(file.isFile)
        assertEquals("dot.jpg", file.name)
        assertTrue(scene.node.children.none { it.name == "media" && it["missing"] == "true" })
    }

    @Test fun refusesOldBinaryFlaFiles() {
        val store = ProjectStore(tmp.newFolder("files"))
        try {
            Importer(store).import { ByteArrayInputStream(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0)) }
            fail("imported")
        } catch (e: ImportFailed) {
            assertEquals(R.string.fla_err_old, e.text.id)
        }
    }
}
