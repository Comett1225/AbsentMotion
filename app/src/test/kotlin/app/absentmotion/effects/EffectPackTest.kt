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
        val files = pack.listFiles()!!.filter { it.extension == "json" && it.name !in setOf("index.json", "foreign.json", "releases.json") }.map { it.nameWithoutExtension }
        assertEquals("index.json and the effect files", files.sorted(), index.sorted())
        assertEquals("ids are unique", index.size, index.toSet().size)
        assertEquals("every listed effect parses", index, catalog.definitions.map { it.key })
        assertTrue(catalog.definitions.all { it.id == "absent.${it.key}" && it.category in EffectCatalog.Categories })
    }

    @Test fun shadersDeclareTheContractAndOneUniformPerParam() {
        val declared = Regex("""(layout\s*\(\s*color\s*\)\s*)?uniform\s+(\w+)\s+(\w+)\s*;""")
        for (effect in catalog.definitions) {
            if (effect.native) {
                assertTrue("${effect.key} is not a built-in", effect.category in setOf("move", "repeat", "text") || effect.id in setOf("absent.blur", "absent.glow", "absent.motionblur", "absent.backdrop", "absent.drawingprogress", "absent.stroketaper"))
                assertNull(effect.sksl)
                continue
            }
            val uniforms = declared.findAll(effect.sksl!!).associate { it.groupValues[3] to (it.groupValues[2] to it.groupValues[1].isNotEmpty()) }
            val blurred = "blurred" in uniforms
            val expected = mapOf("image" to ("shader" to false), "size" to ("float2" to false), "time" to ("float" to false)) +
                (if (blurred) mapOf("blurred" to ("shader" to false)) else emptyMap()) +
                (if ("duration" in uniforms) mapOf("duration" to ("float" to false)) else emptyMap()) +
                listOf("backdrop", "below").filter { it in uniforms }.associateWith { "shader" to false } +
                (if ("screen" in uniforms) mapOf("screen" to ("float4" to false)) else emptyMap()) +
                (if ("layer" in uniforms) mapOf("layer" to ("float2" to false)) else emptyMap()) +
                listOf("toLayer", "fromLayer").filter { it in uniforms }.associateWith { "float3x3" to false } +
                effect.params.associate { p ->
                    p.id to when (p.kind) { "point" -> "float2" to false; "color" -> "half4" to true; else -> "float" to false }
                }
            assertEquals(effect.key, expected, uniforms)
            assertTrue("${effect.key} takes a blurred copy, so it needs a 'blur' size", !blurred || effect.params.any { it.id == "blur" && it.kind == "float" })
            assertTrue(effect.key, Regex("""half4\s+main\s*\(\s*float2\s+\w+\s*\)""").containsMatchIn(effect.sksl))
            assertTrue("${effect.key} declares 'pos', the name the GPU backend gives main's argument",
                !Regex("""\b(float2|half2|float|half)\s+pos\b""").containsMatchIn(effect.sksl))
        }
    }

    @Test fun backgroundEffectsReadWhatIsBehindTheLayer() {
        val reads = { input: String -> catalog.definitions.filter { Regex("""uniform\s+shader\s+$input\s*;""").containsMatchIn(it.sksl ?: "") }.map { it.key }.toSet() }
        assertTrue(reads("backdrop").containsAll(listOf("magnifybackground", "maskblur", "glass", "ominoglass", "displacementmap", "polardisplacement")))
        assertEquals(setOf("displacementmap"), reads("below"))
        assertTrue("fillbehind" !in reads("backdrop"))
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

    @Test fun percentParamsAreMarked() {
        val wipe = catalog.definitions.first { it.key == "wipe" }.params
        assertTrue(wipe.first { it.id == "start" }.percent)
        assertTrue(!wipe.first { it.id == "angle" }.percent)
    }

    @Test fun choicesHaveLabelsAndAWholeDefault() {
        val choices = catalog.definitions.flatMap { e -> e.params.filter { it.kind == "choice" }.map { e.key to it } }
        assertTrue(choices.isNotEmpty())
        for ((key, p) in choices) {
            assertTrue("$key.${p.id}", p.options.size >= 2 && p.min == 0f && p.max == p.options.size - 1f && p.type == "float")
            assertEquals("$key.${p.id}", p.default.toFloat(), p.default.toFloat().toInt().toFloat())
        }
    }

    @Test fun anInstanceHoldsEveryValueAtItsDefault() {
        val effect = catalog.definitions.first { it.params.isNotEmpty() }
        val node = effect.instance()
        assertEquals(effect.id, node["id"])
        assertEquals(effect.params.map { Triple(it.id, it.type, it.default) }, node.children.map { Triple(it["name"], it["type"], it["value"]) })
    }

    @Test fun theNewCategoryShowsEffectsFromTheLastFiveReleases() {
        val releases = JSONArray(File(pack, "releases.json").readText()).let { a -> List(a.length()) { a.getString(it) } }
        val numbers = releases.map { r -> r.split('.').map { it.toInt() } }
        assertTrue("releases are listed oldest first", numbers.zipWithNext().all { (a, b) -> a.zip(b).firstOrNull { it.first != it.second }?.let { it.first < it.second } == true })
        assertTrue(catalog.definitions.all { it.since == null || it.since in releases })
        assertEquals(releases.takeLast(5).toSet(), catalog.newest)
        val fresh = catalog.listed("new").map { it.key }.toSet()
        assertTrue(fresh.containsAll(listOf("vhs", "oscillate", "timequant", "unsharpmask", "flicker")))
        assertTrue("blur" !in fresh && "glow" !in fresh)
        assertEquals(listOf("new") + EffectCatalog.Categories.keys + "ai", catalog.categories.map { it.first })
        val old = EffectCatalog(catalog.definitions.map { it.copy(since = null) }, newest = catalog.newest)
        assertTrue(old.listed("new").isEmpty())
        assertEquals(EffectCatalog.Categories.keys.toList() + "ai", old.categories.map { it.first })
    }

    @Test fun thumbnailsStaySmallAndMovingEffectsAnimate() {
        val thumbs = File(pack, "thumb").listFiles()!!.filter { it.extension == "webp" }
        for (thumb in thumbs) assertTrue("${thumb.name} is ${thumb.length()} bytes", thumb.length() <= 150_000)
        val animated = thumbs.filter { String(it.readBytes().copyOf(64), Charsets.ISO_8859_1).contains("ANIM") }.map { it.nameWithoutExtension }.toSet()
        val wanted = catalog.listed("move").map { it.key } + catalog.listed("3d").map { it.key } + catalog.listed("text").map { it.key } + listOf("vhs", "flicker", "blink", "glitch", "static")
        assertEquals("still pictures", emptyList<String>(), wanted.filter { it !in animated })
        assertTrue("blur is a still picture", "blur" !in animated)
    }

    @Test fun everyEffectHasAThumbnailOnceTheyAreMade() {
        if (!File(pack, "thumb").isDirectory) return
        for (effect in catalog.definitions) assertTrue(effect.thumbnail, File(pack, effect.thumbnail).isFile)
    }
}
