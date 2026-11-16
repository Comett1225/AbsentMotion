package app.absentmotion.library

import app.absentmotion.editor.presetScene
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class CommunityTest {
    @get:Rule val tmp = TemporaryFolder()

    private val shaA = "a".repeat(64)
    private val shaB = "0123456789abcdef".repeat(4)

    @Test fun parseCommunityIndexReadsValidItemsAndIgnoresUnknownKind() {
        val json = """
            {
              "format": "absentmotion-community",
              "version": 2,
              "items": [
                {
                  "kind": "bundle",
                  "name": "neon-intro",
                  "title": "Neon Intro",
                  "creator": "Alice",
                  "description": "Fast glow opener",
                  "tags": ["intro", "neon"],
                  "added": "2026-04-01",
                  "file": "bundles/neon-intro/neon-intro.absproj",
                  "bytes": 2048,
                  "sha256": "$shaA",
                  "thumbnail": "bundles/neon-intro/thumb.webp",
                  "width": 1080,
                  "height": 1920,
                  "lengthMs": 5000
                },
                {
                  "kind": "preset",
                  "name": "pop-scale",
                  "title": "Pop Scale",
                  "creator": "Bob",
                  "description": "Elastic pop",
                  "tags": ["scale"],
                  "added": "2026-04-02",
                  "file": "presets/pop-scale/pop-scale.xml",
                  "bytes": 512,
                  "sha256": "$shaB"
                },
                {
                  "kind": "tool",
                  "name": "custom-tool",
                  "title": "Future Tool",
                  "creator": "Carol",
                  "description": "Ignored kind",
                  "tags": [],
                  "added": "2026-04-03",
                  "file": "tools/custom-tool/tool.zip",
                  "bytes": 100,
                  "sha256": "$shaA"
                }
              ]
            }
        """.trimIndent()

        val index = parseCommunityIndex(json, 52)
        assertFalse(index.tooNew)
        assertEquals(2, index.items.size)

        val bundle = index.items[0]
        assertEquals(CommunityKind.Bundle, bundle.kind)
        assertEquals("neon-intro", bundle.name)
        assertEquals("Neon Intro", bundle.title)
        assertEquals("Alice", bundle.creator)
        assertEquals("Fast glow opener", bundle.description)
        assertEquals(listOf("intro", "neon"), bundle.tags)
        assertEquals("2026-04-01", bundle.added)
        assertEquals("bundles/neon-intro/neon-intro.absproj", bundle.file)
        assertEquals(2048L, bundle.bytes)
        assertEquals(shaA, bundle.sha256)
        assertEquals("bundles/neon-intro/thumb.webp", bundle.thumbnail)
        assertEquals(1080, bundle.width)
        assertEquals(1920, bundle.height)
        assertEquals(5000L, bundle.lengthMs)

        val preset = index.items[1]
        assertEquals(CommunityKind.Preset, preset.kind)
        assertEquals("pop-scale", preset.name)
        assertNull(preset.thumbnail)
    }

    @Test fun parseCommunityIndexSkipsBadItemsClipsTextAndFiltersMinVersion() {
        val longTitle = "T".repeat(120)
        val longCreator = "C".repeat(120)
        val longDesc = "D".repeat(700)
        val tagsJson = (1..12).joinToString(",") { "\"tag-${"x".repeat(40)}-$it\"" }
        val json = """
            {
              "format": "absentmotion-community",
              "version": 2,
              "items": [
                {
                  "kind": "bundle",
                  "name": "good-one",
                  "title": " $longTitle ",
                  "creator": " $longCreator ",
                  "description": " $longDesc ",
                  "tags": [$tagsJson, ""],
                  "added": "2026-04-01",
                  "file": "bundles/good-one/good.absproj",
                  "bytes": 1024,
                  "sha256": "$shaA",
                  "minVersion": 52
                },
                {
                  "kind": "bundle",
                  "name": "too-new-item",
                  "title": "Future",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "bundles/too-new-item/a.absproj",
                  "bytes": 1024,
                  "sha256": "$shaA",
                  "minVersion": 53
                },
                {
                  "kind": "bundle",
                  "name": "Bad_Name",
                  "title": "Bad",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "bundles/Bad_Name/a.absproj",
                  "bytes": 1024,
                  "sha256": "$shaA"
                },
                {
                  "kind": "bundle",
                  "name": "bad-path",
                  "title": "Bad",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "bundles/other/a.absproj",
                  "bytes": 1024,
                  "sha256": "$shaA"
                },
                {
                  "kind": "bundle",
                  "name": "bad-thumb",
                  "title": "Bad",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "bundles/bad-thumb/a.absproj",
                  "thumbnail": "../escape.webp",
                  "bytes": 1024,
                  "sha256": "$shaA"
                },
                {
                  "kind": "preset",
                  "name": "too-big-preset",
                  "title": "Big",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "presets/too-big-preset/a.xml",
                  "bytes": ${(1L shl 20) + 1L},
                  "sha256": "$shaA"
                },
                {
                  "kind": "bundle",
                  "name": "bad-sha",
                  "title": "Sha",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "bundles/bad-sha/a.absproj",
                  "bytes": 100,
                  "sha256": "${"A".repeat(64)}"
                },
                {
                  "kind": "bundle",
                  "name": "zero-bytes",
                  "title": "Zero",
                  "creator": "X",
                  "description": "",
                  "tags": [],
                  "added": "2026-04-01",
                  "file": "bundles/zero-bytes/a.absproj",
                  "bytes": 0,
                  "sha256": "$shaA"
                }
              ]
            }
        """.trimIndent()

        val index = parseCommunityIndex(json, 52)
        assertFalse(index.tooNew)
        assertEquals(1, index.items.size)
        val item = index.items.single()
        assertEquals("good-one", item.name)
        assertEquals(80, item.title.length)
        assertEquals(80, item.creator.length)
        assertEquals(500, item.description.length)
        assertEquals(8, item.tags.size)
        assertTrue(item.tags.all { it.length == 30 })

        val filteredOut = parseCommunityIndex(json, 51)
        assertTrue(filteredOut.items.isEmpty())
    }

    @Test fun parseCommunityIndexMarksVersion3TooNew() {
        val json = """{"format":"absentmotion-community","version":3,"items":[]}"""
        val index = parseCommunityIndex(json, 52)
        assertTrue(index.tooNew)
        assertTrue(index.items.isEmpty())

        val wrongFormat = parseCommunityIndex("""{"format":"other","version":2,"items":[]}""", 52)
        assertTrue(wrongFormat.tooNew)
    }

    @Test fun safeCommunityPathRejectsTraversalAndWrongFolders() {
        assertTrue(safeCommunityPath("bundles/a/a.absproj", "bundles/"))
        assertTrue(safeCommunityPath("bundles/a/a.absproj", "bundles/a/"))
        assertFalse(safeCommunityPath("../x", "bundles/"))
        assertFalse(safeCommunityPath("/x", "bundles/"))
        assertFalse(safeCommunityPath("bundles/a/../b", "bundles/"))
        assertFalse(safeCommunityPath("bundles//a", "bundles/"))
        assertFalse(safeCommunityPath("presets/a/x", "bundles/"))
        assertFalse(safeCommunityPath("https://x", "bundles/"))
        assertFalse(safeCommunityPath("bundles/a/", "bundles/"))
        assertFalse(safeCommunityPath("bundles2/a/a.absproj", "bundles"))
    }

    @Test fun communitySourceAllowsOnlyExactRawRepoOverHttps() {
        fun allows(url: String) = CommunitySource.allows(URI.create(url), true)
        val index = "${COMMUNITY_RAW}index.json"
        assertTrue(allows(COMMUNITY_RAW))
        assertTrue(allows(index))
        assertTrue(allows(COMMUNITY_RAW.uppercase().replace("HTTPS://", "https://") + "bundles/a/a.absproj"))
        assertFalse(allows("https://raw.githubusercontent.com/someone/other/main/index.json"))
        assertFalse(allows(index.replace("raw.githubusercontent.com", "github.com")))
        assertFalse(allows(index.replace("https://", "http://")))
        assertFalse(allows(index.replace(".com/", ".com:444/")))
        assertFalse(allows(index.replace("https://", "https://user@")))
        assertFalse(allows("${COMMUNITY_RAW}bundles/../other"))
        assertFalse(allows("${COMMUNITY_RAW}bundles/%2e%2e/other"))
        assertFalse(allows("$index#frag"))
    }

    @Test fun addCommunityPresetSavesOneLayerSceneAndRejectsEmptyScene() {
        val dir = tmp.newFolder("presets")
        val store = PresetStore(dir)
        val project = Scene.create("p", 1080, 1920, 60, 0)
        val layer = Layer(
            XmlNode(
                "shape",
                linkedMapOf(
                    "id" to "1",
                    "label" to "Fresh",
                    "startTime" to "0",
                    "endTime" to "1000",
                    "fillType" to "color",
                    "s" to ".rect"
                ),
                listOf(XmlNode("shadow"))
            )
        )
        val preset = presetScene(project, layer, "Pulse")
        val xmlFile = tmp.newFile("pulse.xml")
        xmlFile.writeText(preset.toXml())

        val id = addCommunityPreset(xmlFile, store)
        val saved = store.list()
        assertEquals(1, saved.size)
        assertEquals(id, saved[0].id)
        assertEquals("Pulse", saved[0].title)
        assertNotNull(saved[0].layer)

        val emptyFile = tmp.newFile("empty.xml")
        emptyFile.writeText(Scene.create("Empty", 1080, 1920, 30, 0).toXml())
        assertThrows(IllegalArgumentException::class.java) {
            addCommunityPreset(emptyFile, store)
        }

        val bigFile = File(tmp.newFolder("big"), "oversized.xml")
        bigFile.writeBytes(ByteArray((1L shl 20).toInt() + 1))
        assertThrows(IllegalArgumentException::class.java) {
            addCommunityPreset(bigFile, store)
        }
    }

    @Test fun toolItemsNeedAKnownApiPermissionsAndACheckedIcon() {
        fun tool(name: String, extra: String) = """
            {"kind": "tool", "name": "$name", "title": "T", "creator": "C", "description": "D", "tags": [], "added": "2026-11-16",
             "file": "tools/$name/tool.json", "bytes": 900, "sha256": "$shaA", "thumbnail": "tools/$name/icon.png",
             "version": "1.0.0", $extra}
        """
        val good = "\"api\": 1, \"permissions\": [\"media.read\"], \"where\": [\"home\"], \"thumbnailBytes\": 4000, \"thumbnailSha256\": \"$shaB\""
        val json = """{"format": "absentmotion-community", "version": 2, "items": [
            ${tool("good", good)},
            ${tool("future", good.replace("\"api\": 1", "\"api\": 2"))},
            ${tool("network", good.replace("media.read", "network"))},
            ${tool("unchecked", good.replace(", \"thumbnailSha256\": \"$shaB\"", ""))},
            ${tool("nowhere", good.replace("[\"home\"]", "[]"))},
            ${tool("newer", "$good, \"minVersion\": 99")}
        ]}"""
        val items = parseCommunityIndex(json, 52).items
        assertEquals(listOf("good"), items.map { it.name })
        val item = items.single()
        assertEquals(CommunityKind.Tool, item.kind)
        assertEquals(listOf("media.read"), item.permissions)
        assertEquals(listOf("home"), item.where)
        assertEquals("1.0.0", item.version)
        assertEquals(4000L, item.thumbnailBytes)
        assertEquals(shaB, item.thumbnailSha256)
    }
}
