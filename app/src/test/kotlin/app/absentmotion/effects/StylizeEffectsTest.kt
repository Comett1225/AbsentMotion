package app.absentmotion.effects

import app.absentmotion.compat.EffectMigration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StylizeEffectsTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun effect(key: String) = catalog.find("absent.$key")!!
    private fun values(node: XmlNode, name: String) = node.children.associate { it[name]!! to it["value"]!! }

    @Test fun correctedDefaultsKeepThePreviousFallbackForSavedEffects() {
        val expected = mapOf(
            "blink" to mapOf("frequency" to ("2.000000" to "4.000000")),
            "flicker" to mapOf("speed" to ("7.000000" to "12.000000"), "intensity" to ("0.750000" to "0.400000"), "seed" to ("0.000000" to "1.000000")),
            "contourstrips" to mapOf("thickness" to ("0.005000" to "0.000000"), "edgeColor" to ("#ff223355" to "#ffffffff"),
                "color1" to ("#ffff9933" to "#ffffd23c"), "color2" to ("#ffaa3399" to "#ffff3c78")),
            "ominodiffusion" to mapOf("color2" to ("#ff5555ff" to "#ffffffff"), "color3" to ("#ff55ff55" to "#ffff3c3c"),
                "color4" to ("#ff55ffff" to "#ff3cff6e"), "color5" to ("#ffff5555" to "#ff3c78ff"), "color6" to ("#ffff55ff" to "#ffffd23c")),
            "radialrays" to mapOf("count" to ("5.000000" to "12.000000"), "color" to ("#ff2d1ef6" to "#ffffcc33")),
            "radialshadow" to mapOf("reach" to ("0.250000" to "0.500000"), "alpha" to ("0.100000" to "0.600000"), "fade" to ("0.000000" to "0.500000")),
            "rays" to mapOf("center" to ("0.500000,0.500000" to "0.500000,0.300000"), "reach" to ("0.150000" to "0.400000"), "color" to ("#ff2d1ef6" to "#fffff2d0"),
                "blend" to ("0.000000" to "1.000000"), "thresholdAdd" to ("#ff000000" to "#ff000000"), "style" to ("1.000000" to "0.000000")),
            "roughenedges" to mapOf("octaves" to ("6.000000" to "4.000000")),
            "softglow" to mapOf("color" to ("#ffff5566" to "#ffffffff"), "opacity" to ("1.000000" to "0.800000")),
            "star" to mapOf("color" to ("#ff2d1ef6" to "#ffffffff"), "blend" to ("2.000000" to "6.000000")),
            "voronoicells" to mapOf("color1" to ("#ffffffff" to "#ff000000"), "color2" to ("#ff000000" to "#ffffffff"), "lineColor" to ("#ff0000ff" to "#ff000000")),
        )
        for ((key, params) in expected) {
            val definition = effect(key)
            val fresh = values(definition.instance(), "name")
            val saved = values(XmlNode.parse(definition.runtimeXml()), "name")
            for ((id, pair) in params) {
                assertEquals("$key.$id new", pair.first, fresh[id])
                assertEquals("$key.$id saved", pair.second, saved[id])
                assertEquals(pair.second, definition.params.single { it.id == id }.missingDefault)
            }
        }
        assertEquals("Hz", effect("blink").params.single().unit)
        assertEquals(listOf("Frequency", "Strength", "Seed"), effect("flicker").params.map { it.label })
        assertEquals(20f, effect("sharpen").params.first().max)
        assertEquals(10f, effect("sharpen").params.last().max)
        assertEquals(4f, effect("rays").params.single { it.id == "reach" }.max)
        assertEquals(5f, effect("rays").params.single { it.id == "intensity" }.max)
    }

    @Test fun screenSpaceIsOptInAndOldEffectsHaveTheSameUniformValues() {
        for (key in listOf("checker", "stripes")) {
            val definition = effect(key)
            val param = definition.params.single { it.id == "screenSpace" }
            assertEquals("switch", param.kind)
            assertEquals("false", param.default)
            assertEquals("false", param.missingDefault)
        }
    }

    @Test fun alightImportsUseAlightDefaultsAndKeepPatternKeyframes() {
        val source = Scene.parse("""<scene width="200" height="200" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect">
            <effect id="com.alightcreative.effects.blink2"/>
            <effect id="com.alightcreative.effects.flicker2"/>
            <effect id="com.alightcreative.effects.softglow"/>
            <effect id="com.alightcreative.effects.checker2"><property name="position" type="vec2"><kf t="0" v="0,0"/><kf t="1" v="12,5"/></property><property name="screenSpace" type="bool" value="true"/></effect>
            <effect id="com.alightcreative.effects.dots2"><property name="color" type="color" value="#80445566"/><property name="blendMode" type="int" value="2"/></effect>
            </shape></scene>""")
        val result = migration.migrate(source)
        val effects = result.scene.node.children.single().children
        assertEquals("2.000000", values(effects[0], "name")["frequency"])
        assertEquals(mapOf("speed" to "7.000000", "intensity" to "0.750000", "seed" to "0.000000"), values(effects[1], "name"))
        assertEquals("#ffff5566", values(effects[2], "name")["color"])
        assertEquals("1.000000", values(effects[2], "name")["opacity"])
        assertEquals("absent.checker", effects[3]["id"])
        assertEquals("true", effects[3].children.single { it["name"] == "screenSpace" }["value"])
        val offset = effects[3].children.single { it["name"] == "offset" }
        assertEquals(listOf("0.000000,0.000000", "12.000000,5.000000"), offset.children.map { it["v"] })
        assertEquals("absent.dots", effects[4]["id"])
        assertEquals("#80445566", values(effects[4], "name")["color1"])
        assertEquals("2.000000", values(effects[4], "name")["blend"])
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
    }

    @Test fun raysClassicIsTheShaderOfVersion411() {
        val sksl = effect("rays").sksl!!
        val start = sksl.indexOf("half4 classic(float2 coord) {")
        val end = sksl.indexOf("\nhalf4 main")
        assertTrue(start >= 0 && end > start)
        assertEquals(CLASSIC_RAYS, sksl.substring(start, end))
        assertTrue(sksl.contains("\nfloat luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }\n"))
        assertTrue(sksl.contains("\nhalf4 main(float2 p) {\n    if (style < 0.5) return classic(p);\n"))
    }

    private companion object {
        const val CLASSIC_RAYS = """half4 classic(float2 coord) {
    half4 src = image.eval(coord);
    float2 c = center * size;
    float length_ = max(reach, 0.0);
    float sum = 0.0;
    for (int i = 0; i < 48; i++) {
        float t = float(i) / 47.0;
        half4 s = image.eval(c + (coord - c) * (1.0 - length_ * t / (1.0 + length_)));
        float l = s.a > 0.0 ? luma(float3(s.rgb) / float(s.a)) * float(s.a) : 0.0;
        sum += max(l - threshold, 0.0) / max(1.0 - threshold, 0.001) * (1.0 - t);
    }
    float g = clamp(sum / 24.0 * intensity * float(color.a), 0.0, 1.0);
    float a = float(src.a);
    float3 base = a > 0.0 ? float3(src.rgb) / a : float3(0.0);
    float3 light = float3(color.rgb) * g;
    float outA = max(a, g);
    float3 rgb = outA > 0.0 ? (base * a + light - base * a * light) / outA : float3(0.0);
    return half4(half3(clamp(rgb, 0.0, 1.0)) * half(outA), half(outA));
}"""
    }
}
