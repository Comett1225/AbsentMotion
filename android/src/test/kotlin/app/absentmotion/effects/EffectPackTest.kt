package app.absentmotion.effects

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectPackTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }

    private fun reads(input: String) = catalog.definitions.filter { Regex("""uniform\s+shader\s+$input\s*;""").containsMatchIn(it.sksl ?: "") }.map { it.key }.toSet()

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
                assertTrue("${effect.key} is not a built-in", effect.category in setOf("move", "repeat", "text") || effect.id in setOf("absent.blur", "absent.glow", "absent.motionblur", "absent.smear", "absent.backdrop", "absent.drawingprogress", "absent.stroketaper", "absent.puppet", "absent.particles", "absent.scatter3d", "absent.fracture3d"))
                assertNull(effect.sksl)
                continue
            }
            val uniforms = declared.findAll(effect.sksl!!).associate { it.groupValues[3] to (it.groupValues[2] to it.groupValues[1].isNotEmpty()) }
            val copies = listOf("blurred", "bright").filter { it in uniforms }
            val blurred = copies.isNotEmpty()
            val expected = mapOf("image" to ("shader" to false), "size" to ("float2" to false), "time" to ("float" to false)) +
                copies.associateWith { "shader" to false } +
                (if ("duration" in uniforms) mapOf("duration" to ("float" to false)) else emptyMap()) +
                listOf("backdrop", "below").filter { it in uniforms }.associateWith { "shader" to false } +
                (if ("screen" in uniforms) mapOf("screen" to ("float4" to false)) else emptyMap()) +
                (if ("visible" in uniforms) mapOf("visible" to ("float4" to false)) else emptyMap()) +
                (if ("layer" in uniforms) mapOf("layer" to ("float2" to false)) else emptyMap()) +
                listOf("toLayer", "fromLayer", "orient").filter { it in uniforms }.associateWith { "float3x3" to false } +
                listOf("palette", "thresholds", "glyphs").filter { it in uniforms }.associateWith { "shader" to false } +
                listOf("glyphCount", "glyphColumns").filter { it in uniforms }.associateWith { "float" to false } +
                (if ("glyphCell" in uniforms) mapOf("glyphCell" to ("float2" to false)) else emptyMap()) +
                listOf("paletteSize", "paletteSpread").filter { it in uniforms }.associateWith { "float" to false } +
                listOf("beat", "beatTime", "beatIndex").filter { it in uniforms }.associateWith { "float" to false } +
                effect.params.filter { it.kind != "text" }.associate { p ->
                    p.id to when (p.kind) { "point" -> "float2" to false; "color" -> "half4" to true; else -> "float" to false }
                }
            assertEquals(effect.key, expected, uniforms)
            assertTrue("${effect.key} takes a blurred copy, so it needs a 'blur' size", !blurred || effect.params.any { it.id == "blur" && it.kind == "float" })
            assertTrue(effect.key, Regex("""half4\s+main\s*\(\s*float2\s+\w+\s*\)""").containsMatchIn(effect.sksl))
            assertTrue("${effect.key} declares 'pos', the name the GPU backend gives main's argument",
                !Regex("""\b(float2|half2|float|half)\s+pos\b""").containsMatchIn(effect.sksl))
        }
    }

    @Test fun threeDimensionalPiecesExposeTheirControls() {
        val scatter = catalog.find("absent.scatter3d")!!
        val fracture = catalog.find("absent.fracture3d")!!
        assertEquals("3d", scatter.category)
        assertEquals("3d", fracture.category)
        assertEquals(listOf("Cube", "Sphere", "Square", "Circle"), scatter.params.first { it.id == "shape" }.options)
        assertEquals(24f, scatter.params.first { it.id == "spacing" }.default.toFloat(), 0f)
        assertEquals(1f, scatter.params.first { it.id == "depth" }.default.toFloat(), 0f)
        assertEquals(0f, fracture.params.first { it.id == "progress" }.default.toFloat(), 0f)
        assertEquals(40f, fracture.params.first { it.id == "pieces" }.default.toFloat(), 0f)
        assertTrue(fracture.params.any { it.id == "impact" && it.kind == "point" })
    }

    @Test fun backgroundEffectsReadWhatIsBehindTheLayer() {
        assertTrue(reads("backdrop").containsAll(listOf("magnifybackground", "maskblur", "glass", "ominoglass", "liquidglass", "displacementmap", "polardisplacement")))
        assertEquals(setOf("displacementmap"), reads("below"))
        assertTrue("fillbehind" !in reads("backdrop"))
    }

    @Test fun onlyLightGlowBlursTheBrightParts() {
        assertEquals(setOf("lightglow"), reads("bright"))
        assertTrue(reads("blurred").containsAll(listOf("darkglow", "edgeglow")))
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

    @Test fun sphereProjectionAndTunnelPhaseKeepTheirLegacyDefaults() {
        val sphere = catalog.find("absent.spherize")!!
        val projection = sphere.params.single { it.id == "projection" }
        assertEquals(listOf("Front", "Equirectangular"), projection.options)
        assertEquals("0.000000", projection.default)
        val phase = catalog.find("absent.tunnel")!!.params.single { it.id == "phase" }
        assertEquals("Phase", phase.label)
        assertEquals("0.000000", phase.default)
    }

    @Test fun theNewCategoryShowsOnlyEffectsFromTheLastFiveReleases() {
        val releases = JSONArray(File(pack, "releases.json").readText()).let { a -> List(a.length()) { a.getString(it) } }
        val numbers = releases.map { r -> r.split('.').map { it.toInt() } }
        assertTrue("releases are listed oldest first", numbers.zipWithNext().all { (a, b) -> a.zip(b).firstOrNull { it.first != it.second }?.let { it.first < it.second } == true })
        val last = numbers.last()
        assertTrue(catalog.definitions.all { d ->
            d.since == null || d.since in releases || d.since.split('.').map { it.toInt() }.zip(last).firstOrNull { it.first != it.second }?.let { it.first > it.second } == true
        })
        val major = (releases + catalog.definitions.mapNotNull { it.since }).maxOf { it.substringBefore('.').toInt() }
        assertTrue(catalog.newest.isNotEmpty() && catalog.newest.all { it.substringBefore('.').toInt() == major })
        val fresh = catalog.listed("new").map { it.key }.toSet()
        assertEquals(catalog.definitions.filter { it.since?.substringBefore('.')?.toInt() == major }.map { it.key }.toSet(), fresh)
        assertTrue(fresh.none { it in listOf("textpath", "puppet") })
        assertTrue(listOf("blur", "glow", "vhs", "oscillate", "timequant").none { it in fresh })
        assertEquals(File("../version.properties").readLines().first { it.startsWith("versionName=") }.removePrefix("versionName="), releases.last())
        assertEquals(listOf("new") + EffectCatalog.Categories.keys + "ai", catalog.categories.map { it.first })
        val old = EffectCatalog(catalog.definitions.map { it.copy(since = null) }, newest = catalog.newest)
        assertTrue(old.listed("new").isEmpty())
        assertEquals(EffectCatalog.Categories.keys.toList() + "ai", old.categories.map { it.first })
    }

    @Test fun thumbnailsStaySmallAndMovingEffectsAnimate() {
        val thumbs = File(pack, "thumb").listFiles()!!.filter { it.extension == "webp" }
        for (thumb in thumbs) assertTrue("${thumb.name} is ${thumb.length()} bytes", thumb.length() <= 150_000)
        val animated = thumbs.filter { String(it.readBytes().copyOf(64), Charsets.ISO_8859_1).contains("ANIM") }.map { it.nameWithoutExtension }.toSet()
        val wanted = catalog.listed("move").map { it.key } + catalog.listed("3d").map { it.key } + catalog.listed("text").map { it.key } +
            catalog.listed("beat").map { it.key } + listOf("vhs", "flicker", "blink", "glitch", "static", "particles")
        assertEquals("still pictures", emptyList<String>(), wanted.filter { it !in animated })
        assertTrue("blur is a still picture", "blur" !in animated)
    }

    @Test fun everyEffectHasAThumbnail() {
        assertTrue("thumb folder", File(pack, "thumb").isDirectory)
        for (effect in catalog.definitions) assertTrue(effect.thumbnail, File(pack, effect.thumbnail).isFile)
    }

    @Test fun particlesShowTheBasicSettingsFirst() {
        val particles = catalog.find("absent.particles")!!
        val plain = particles.instance()
        assertEquals(listOf("kind", "emitter", "emission", "rate", "lifetime", "speed", "direction", "spread", "gravity", "wind", "swirl", "size", "color", "layer", "depth", "more"),
            particles.params.filter { particles.shown(it, plain, 0f) }.map { it.id })
        val depth = plain.copy(children = plain.children.map { if (it["name"] == "depth") it.with("value", "true") else it })
        assertTrue(particles.params.filter { particles.shown(it, depth, 0f) }.map { it.id }.containsAll(listOf("zSpeed", "zSpread", "perspective")))
        val more = plain.copy(children = plain.children.map { if (it["name"] == "more") it.with("value", "true") else it })
        assertTrue(particles.params.filter { particles.shown(it, more, 0f) }.map { it.id }.containsAll(listOf("orientation", "colorMode", "blend", "seed")))
        val beats = plain.copy(children = plain.children.map { if (it["name"] == "emission") it.with("value", "2") else it })
        assertEquals(listOf("count", "beats", "bpm"), particles.params.filter { particles.shown(it, beats, 0f) && !particles.shown(it, plain, 0f) }.map { it.id })
    }

    @Test fun beatEffectsReadTheBeatAndExplainMarkBeats() {
        val beat = catalog.listed("beat")
        assertTrue(beat.size in 6..10)
        for (effect in beat) {
            assertTrue(effect.key, effect.key.startsWith("beat") && Regex("""uniform\s+float\s+beat\s*;""").containsMatchIn(effect.sksl ?: ""))
            assertEquals(effect.key, listOf("strength", "decay", "beats", "offset", "bpm"), effect.params.map { it.id }.filter { it in setOf("strength", "decay", "beats", "offset", "bpm") })
            assertTrue(effect.key, "Mark Beats" in effect.description)
        }
        assertTrue(catalog.definitions.filter { it.category != "beat" }.none { Regex("""uniform\s+float\s+beat(Time|Index)?\s*;""").containsMatchIn(it.sksl ?: "") })
    }
}
