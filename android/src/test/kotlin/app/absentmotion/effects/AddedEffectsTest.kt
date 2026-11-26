package app.absentmotion.effects

import android.content.SharedPreferences
import app.absentmotion.export.writeProjectPackage
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddedEffectsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun effect(id: String = "colour", category: String = "color") = JSONObject()
        .put("id", id).put("name", "Test Colour").put("category", category).put("params", JSONArray())
        .put("sksl", "uniform shader image; half4 main(float2 p) { return image.eval(p); }")

    private fun read(json: JSONObject) = readAddedEffects("effect.json", json.toString().byteInputStream(), temp.root).single()

    private fun pack(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun readsJsonWithSafeIdAndIgnoresFileExtras() {
        val added = read(effect(category = "unknown")
            .put("author", "A. Painter").put("licence", "CC0").put("version", 3)
            .put("thumbnail", "chosen.webp").put("shortcuts", JSONArray().put("ignored"))
            .put("presets", JSONArray().put("ignored")).put("since", "55").put("native", true))
        assertEquals("user.a-painter.colour", added.definition.id)
        assertNull(added.definition.category)
        assertFalse(added.json.has("category"))
        assertFalse(added.definition.native)
        assertNull(added.definition.since)
        assertTrue(added.definition.shortcuts.isEmpty())
        assertTrue(added.definition.presets.isEmpty())
        assertFalse(added.json.has("thumbnail"))
        assertEquals("A. Painter", added.author)
        assertEquals("CC0", added.licence)
        assertEquals(3, added.json.getInt("version"))
    }

    @Test fun keepsOnlyBuiltInCategories() {
        assertEquals("color", read(effect()).definition.category)
        assertEquals("other", read(effect(category = "other")).definition.category)
        assertNull(read(effect().apply { remove("category") }).definition.category)
    }

    @Test fun savesPlacementAndClearsItWhenEffectsAreRemoved() {
        val saved = mutableMapOf<String, Set<String>>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putStringSet" -> {
                    saved[args[0] as String] = (args[1] as Set<*>).filterIsInstance<String>().toSet()
                    proxy
                }
                "apply" -> null
                else -> error(method.name)
            }
        }
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getStringSet" -> saved[args[0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
        val store = AddedEffectStore(temp.root, prefs)
        val first = read(effect().put("author", "A Painter"))
        val second = read(effect("light", "other").put("author", "A Painter"))
        val unknown = read(effect("unknown", "custom"))
        listOf(first, second, unknown).forEach { store.put(it, null) }
        val baseline = EffectCatalog().withAdded(store)
        val firstId = first.definition.id
        val secondId = second.definition.id
        val slug = first.definition.addedCreator
        assertTrue(baseline.listed("color").isEmpty())
        assertTrue(baseline.listed("other").isEmpty())
        assertEquals(3, baseline.listed(AddedEffectCategory).size)
        assertTrue(baseline.search("Test Colour").any { it.id == unknown.definition.id })
        val placed = baseline.placeAddedEffect(store, unknown.definition.id, true)
            .placeAddedEffect(store, firstId, true).placeAddedEffect(store, secondId, true)
            .placeAddedCreator(store, slug, true)
        assertEquals(setOf(firstId, secondId), saved["shown_added_effects"])
        assertEquals(setOf(slug), saved["shown_added_creators"])
        val reopened = EffectCatalog().withAdded(AddedEffectStore(temp.root, prefs))
        assertEquals(placed, reopened)
        assertEquals(listOf(firstId), reopened.listed("color").map { it.id })
        assertEquals(listOf(secondId), reopened.listed("other").map { it.id })

        val (remaining, firstRemoved) = reopened.changeAddedEffects(store, emptyList(), first.definition, { "ok" })
        assertTrue(firstRemoved)
        assertEquals(setOf(secondId), saved["shown_added_effects"])
        assertEquals(setOf(slug), saved["shown_added_creators"])
        val (_, lastRemoved) = remaining.changeAddedEffects(store, emptyList(), second.definition, { "ok" })
        assertTrue(lastRemoved)
        assertEquals(emptySet<String>(), saved["shown_added_effects"])
        assertEquals(emptySet<String>(), saved["shown_added_creators"])
    }

    @Test fun communityPicturesReplaceTheRenderedOneAndKeepTheirHeroUntilRemoved() {
        val store = AddedEffectStore(temp.root)
        val effect = read(effect().put("author", "A Painter"))
        val key = effect.definition.key
        val rendered = listOf(AddedEffectPreview(effect, true, byteArrayOf(1)))
        val provided = rendered.withPictures(mapOf(key to (byteArrayOf(2) to byteArrayOf(3))))
        assertEquals(listOf(2, 3), provided.single().let { listOf(it.picture!!.single().toInt(), it.hero!!.single().toInt()) })
        assertSame(rendered.single(), rendered.withPictures(mapOf("someone.else" to (byteArrayOf(2) to null))).single())
        assertArrayEquals(byteArrayOf(1), rendered.withPictures(mapOf(key to (null to null))).single().picture)
        store.putAll(provided)
        val catalog = EffectCatalog().withAdded(store)
        val added = catalog.find(effect.definition.id)!!
        assertArrayEquals(byteArrayOf(3), catalog.bytes(added.hero))
        assertArrayEquals(byteArrayOf(2), catalog.bytes(added.thumbnail))
        store.putAll(rendered)
        val again = EffectCatalog().withAdded(store).find(effect.definition.id)!!
        assertEquals(again.thumbnail, again.hero)
        store.putAll(provided)
        store.remove(key)
        assertFalse(store.hero(key).exists())
    }

    @Test fun packsOnlyReadRootJsonAndLimitTheirFileCount() {
        val first = effect().toString()
        val second = effect("light").toString()
        val pack = pack("first.json" to first, "second.json" to second, "sub/invalid.json" to "ignored", "readme.txt" to "ignored")
        assertEquals(listOf("user.unknown.colour", "user.unknown.light"),
            readAddedEffects("pack.abmfx", pack.inputStream(), temp.root).map { it.definition.id })
        val many = pack(*Array(21) { "$it.json" to first })
        assertThrows(IllegalArgumentException::class.java) { readAddedEffects("pack.abmfx", many.inputStream(), temp.root) }
    }

    @Test fun rejectsOversizedFilesBadIdsAndTooManySettings() {
        for (name in listOf("effect.json", "pack.abmfx")) {
            val large = ByteArray(AddedEffectMaxBytes + 1)
            assertThrows(IllegalArgumentException::class.java) { readAddedEffects(name, large.inputStream(), temp.root) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(effect("bad.id")) }
        assertThrows(IllegalArgumentException::class.java) { read(effect("a".repeat(41))) }
        assertThrows(IllegalArgumentException::class.java) { read(effect().put("sksl", "a".repeat(64 * 1024 + 1))) }
        val settings = JSONArray(List(33) { JSONObject().put("id", "p$it").put("type", "float").put("default", 0) })
        assertThrows(IllegalArgumentException::class.java) { read(effect().put("params", settings)) }
    }

    @Test fun updatesAndRemovesWithoutChangingEffectEntriesInProjects() {
        val store = AddedEffectStore(temp.root)
        val original = read(effect().put("author", "A. Painter").put("licence", "CC0"))
        val other = read(effect().put("author", "B. Painter"))
        val revised = read(effect().put("name", "New Colour").put("author", "A Painter").put("licence", "MIT").put("version", 2))
        store.put(original, byteArrayOf(1))
        store.put(other, byteArrayOf(3))
        store.put(revised, byteArrayOf(2))
        val catalog = EffectCatalog().withAdded(store)
        val installed = checkNotNull(catalog.find("user.a-painter.colour"))
        assertEquals("New Colour", installed.name)
        assertEquals("Test Colour", catalog.find("user.b-painter.colour")?.name)
        val saved = JSONObject(File(temp.root, "effects-added/a-painter.colour.json").readText())
        assertEquals("A Painter", saved.getString("author"))
        assertEquals("MIT", saved.getString("licence"))
        assertArrayEquals(byteArrayOf(2), catalog.bytes(installed.thumbnail))
        val scene = Scene.parse("""<scene><shape id="1">${installed.instance().toXml().substringAfter("?>")}</shape></scene>""")
        assertTrue(scene.missingAddedEffects(catalog).isEmpty())
        store.remove(installed.key)
        assertEquals(listOf(other.definition.id), store.definitions().map { it.id })
        assertFalse(store.picture(installed.key).exists())
        assertEquals(listOf("New Colour"), Scene.parse(scene.toXml()).missingAddedEffects(EffectCatalog().withAdded(store)))
        assertEquals(installed.instance(), scene.layers.single().node.child("effect"))
    }

    @Test fun creatorSlugsNormalizeAsciiAndUseStableFallbacks() {
        assertEquals("a-painter-42", creatorSlug("  A...Painter & 42! "))
        assertEquals("u77710aed", creatorSlug("日本語"))
        assertEquals("unknown", creatorSlug("  \t "))
        assertEquals("a".repeat(32), creatorSlug("A".repeat(40)))
        assertEquals("user.unknown.colour", read(effect()).definition.id)
    }

    @Test fun versionsChooseUpdateAddAgainOrReplace() {
        assertEquals(1L, read(effect()).definition.addedVersion)
        assertEquals(AddedEffectAction.Add, addedEffectAction(2, null))
        assertEquals(AddedEffectAction.Update, addedEffectAction(3, 2))
        assertEquals(AddedEffectAction.AddAgain, addedEffectAction(2, 2))
        assertEquals(AddedEffectAction.Replace, addedEffectAction(1, 2))
        for (bad in listOf(0, -1, 1.5, "2", JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) { read(effect().put("version", bad)) }
        }
    }

    @Test fun projectSaveAndBundleRoundTripKeepEffectVersionAndSource() = runBlocking {
        val store = AddedEffectStore(temp.root)
        val projects = ProjectStore(temp.root)
        val source = AddedEffectSource("tints", "a".repeat(64))
        val effect = read(effect().put("author", "A. Painter").put("version", 4)).from(source)
        val unused = read(effect("unused"))
        store.put(effect, null)
        store.put(unused, null)
        val installed = checkNotNull(EffectCatalog().withAdded(store).find(effect.definition.id))
        val layerEffect = installed.instance(null, 1000).toXml().substringAfter("?>")
        val scene = Scene.parse("""<scene title="Project" width="320" height="240" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect">$layerEffect</shape></scene>""")
        val saved = checkNotNull(projects.load(projects.create(scene)))
        val node = checkNotNull(saved.layers.single().node.child("effect"))
        assertEquals("4", node["version"])
        assertEquals("tints", node["pack"])
        val file = temp.newFile("effects.absproj")
        file.outputStream().use { out ->
            writeProjectPackage(saved, saved.toXml(), out, MediaAccess.Unlimited, { null }, null, store.bundled(saved)) {}
        }
        val entry = "effects/a-painter.colour.json"
        ZipFile(file).use { zip ->
            assertEquals(listOf(entry), zip.entries().asSequence().map { it.name }.filter { it.startsWith("effects/") }.toList())
        }
        val report = Importer(projects).import { file.inputStream() }.single()
        val bytes = report.bundledEffects.getValue(entry)
        assertArrayEquals(File(temp.root, "effects-added/a-painter.colour.json").readBytes(), bytes)
        val bundled = readBundledAddedEffect(entry, bytes).second.getOrThrow()
        assertEquals(source.sha256, bundled.json.getJSONObject("source").getString("sha256"))
        assertEquals(4L, bundled.definition.addedVersion)
        val imported = checkNotNull(projects.load(report.id))
        assertEquals(node, imported.layers.single().node.child("effect"))
        val older = read(effect().put("author", "A. Painter"))
        store.put(older, null)
        assertEquals(listOf("Test Colour"), imported.missingAddedEffects(EffectCatalog().withAdded(store)))
    }

    @Test fun aFailedWriteLeavesTheWholePackUnchanged() {
        val store = AddedEffectStore(temp.root)
        val original = read(effect())
        store.put(original, byteArrayOf(1))
        val unavailable = read(effect("other"))
        assertTrue(store.picture(unavailable.definition.key).mkdir())
        val update = read(effect().put("version", 2))
        val catalog = EffectCatalog().withAdded(store)
        val runtime = mutableMapOf(original.definition.id to original.definition.runtimeXml())
        val (result, saved) = catalog.changeAddedEffects(store, listOf(
            AddedEffectPreview(update, true, byteArrayOf(2)),
            AddedEffectPreview(unavailable, true, byteArrayOf(3)),
        ), null, load = { xml ->
            val node = app.absentmotion.model.XmlNode.parse(xml)
            val id = checkNotNull(node["id"])
            if (node["remove"] == "true") runtime.remove(id) else runtime[id] = xml
            "ok"
        })
        assertFalse(saved)
        assertEquals(1L, result.definitions.single().addedVersion)
        assertArrayEquals(byteArrayOf(1), store.picture(original.definition.key).readBytes())
        assertEquals(mapOf(original.definition.id to original.definition.runtimeXml()), runtime)
        assertFalse(File(temp.root, "effects-added/unknown.other.json").exists())
        assertFalse(temp.root.listFiles().orEmpty().any { it.name.startsWith("effects-added-") })
    }
}
