package app.absentmotion.effects

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectPackTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }

    @Test fun everyEffectFileIsListedAndLoads() {
        assertNull(catalog.message)
        val index = JSONArray(File(pack, "index.json").readText()).let { a -> List(a.length()) { a.getString(it) } }
        val files = pack.listFiles()!!.filter { it.extension == "json" && it.name != "index.json" }.map { it.nameWithoutExtension }
        assertEquals("index.json and the effect files", files.sorted(), index.sorted())
        assertEquals("ids are unique", index.size, index.toSet().size)
        assertEquals("every listed effect parses", index, catalog.definitions.map { it.key })
        assertTrue(catalog.definitions.all { it.id == "absent.${it.key}" && it.category in EffectCatalog.Categories })
    }

    @Test fun shadersDeclareTheContractAndOneUniformPerParam() {
        val declared = Regex("""(layout\s*\(\s*color\s*\)\s*)?uniform\s+(\w+)\s+(\w+)\s*;""")
        for (effect in catalog.definitions) {
            if (effect.native) {
                assertTrue("${effect.key} is not a built-in", effect.id in setOf("absent.blur", "absent.glow", "absent.solid", "absent.motionblur"))
                assertNull(effect.sksl)
                continue
            }
            val uniforms = declared.findAll(effect.sksl!!).associate { it.groupValues[3] to (it.groupValues[2] to it.groupValues[1].isNotEmpty()) }
            val expected = mapOf("image" to ("shader" to false), "size" to ("float2" to false), "time" to ("float" to false)) +
                effect.params.associate { p ->
                    p.id to when (p.kind) { "point" -> "float2" to false; "color" -> "half4" to true; else -> "float" to false }
                }
            assertEquals(effect.key, expected, uniforms)
            assertTrue(effect.key, Regex("""half4\s+main\s*\(\s*float2\s+\w+\s*\)""").containsMatchIn(effect.sksl))
        }
    }

    @Test fun defaultsLieWithinTheirRange() {
        for (effect in catalog.definitions) for (p in effect.params) {
            if (p.kind !in setOf("float", "angle", "point")) continue
            assertTrue("${effect.key}.${p.id}", p.min <= p.max && p.step > 0)
            p.default.split(',').forEach {
                assertTrue("${effect.key}.${p.id} = ${p.default}", it.toFloat() in p.min..p.max)
            }
        }
    }

    @Test fun anInstanceHoldsEveryValueAtItsDefault() {
        val effect = catalog.definitions.first { it.params.isNotEmpty() }
        val node = effect.instance()
        assertEquals(effect.id, node["id"])
        assertEquals(effect.params.map { Triple(it.id, it.type, it.default) }, node.children.map { Triple(it["name"], it["type"], it["value"]) })
    }

    @Test fun everyEffectHasAThumbnailOnceTheyAreMade() {
        if (!File(pack, "thumb").isDirectory) return
        for (effect in catalog.definitions) assertTrue(effect.thumbnail, File(pack, effect.thumbnail).isFile)
    }
}
