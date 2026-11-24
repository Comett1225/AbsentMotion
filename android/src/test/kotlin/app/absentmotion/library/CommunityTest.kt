package app.absentmotion.library

import app.absentmotion.editor.presetScene
import app.absentmotion.effects.AddedEffectUse
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

    @Test fun releaseAssetsAreOptionalPlainFileNames() {
        fun item(field: String) = parseCommunityIndex("""
            {"format":"absentmotion-community","version":2,"items":[
            {"kind":"effect","name":"fun-pack","file":"effects/fun-pack/fun-pack.abmfx","bytes":42,"sha256":"$shaA"$field}
            ]}
        """.trimIndent(), 52).items.single()

        fun withAsset(value: String) = item(",\"asset\":" + org.json.JSONObject.quote(value))
        val release = withAsset("effect-fun-pack.abmfx")
        assertEquals("effect-fun-pack.abmfx", release.asset)
        assertEquals("https://github.com/Comett1225/AbsentMotion-Community/releases/download/items/effect-fun-pack.abmfx", communityDownloadUrl(release))
        assertNotNull(withAsset("x".repeat(120)).asset)
        val invalid = listOf("", "../asset", "a b", "a%2Fb", "..", "x".repeat(121)).map(::withAsset)
        for (raw in invalid + listOf(item(""), item(",\"asset\":42"), item(",\"asset\":null"))) {
            assertNull(raw.asset)
            assertEquals(COMMUNITY_RAW + raw.file, communityDownloadUrl(raw))
        }
    }

    @Test fun releaseDownloadsTrustOnlyTheUpdatersAssetHostsAfterRedirect() {
        for (host in listOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")) {
            val target = URI("https://$host/asset?ticket=123")
            assertTrue(CommunityReleaseSource.allows(target, first = false))
            assertFalse(CommunityReleaseSource.allows(target, first = true))
        }
        assertFalse(CommunityReleaseSource.allows(URI("https://raw.githubusercontent.com/asset"), first = false))
        assertFalse(CommunityReleaseSource.allows(URI("http://release-assets.githubusercontent.com/asset"), first = false))
    }

    @Test fun releaseCountsReadOnlyValidAssetNamesAndNonnegativeIntegers() {
        val counts = parseCommunityDownloadCounts("""
            {"assets":[
              {"name":"effect-fun-pack.abmfx","download_count":1234},
              {"name":"preset-zero.xml","download_count":0},
              {"name":"bundle-large.absproj","download_count":2147483648},
              {"name":"bundle-missing.absproj"},
              {"name":"preset-negative.xml","download_count":-1},
              {"name":"preset-decimal.xml","download_count":1.5},
              {"name":"preset-string.xml","download_count":"50"},
              {"name":"../item","download_count":1}
            ]}
        """.trimIndent())
        assertEquals(mapOf("effect-fun-pack.abmfx" to 1234L, "preset-zero.xml" to 0L, "bundle-large.absproj" to 2147483648L), counts)
    }

    @Test fun releaseCountsCacheLastsAnHourInMemoryAndOnDisk() {
        var now = 1000L
        val file = File(tmp.root, "community/counts.json")
        val cache = CommunityCountsCache(file) { now }
        val fresh = mapOf("bundle-clip.absproj" to 5L)
        assertEquals(fresh, cache.counts { fresh })
        now += COMMUNITY_COUNTS_TTL_MS - 1
        assertEquals(fresh, cache.counts { throw AssertionError("memory cache missed") })
        assertEquals(fresh, CommunityCountsCache(file) { now }.counts { throw AssertionError("disk cache missed") })
        now += 1
        assertTrue(cache.counts { throw java.io.IOException() }.isEmpty())
        assertTrue(CommunityCountsCache(file) { now }.counts { throw AssertionError("failure was not cached") }.isEmpty())
        now += COMMUNITY_COUNTS_TTL_MS
        val updated = mapOf("bundle-clip.absproj" to 8L)
        assertEquals(updated, cache.counts { updated })
    }

    @Test fun aCountsFetchStoppedByLeavingTheTabIsNotCached() {
        val file = File(tmp.root, "community/counts.json")
        val cache = CommunityCountsCache(file) { 1000L }
        assertTrue(cache.counts { throw java.io.InterruptedIOException("thread interrupted") }.isEmpty())
        assertFalse(file.exists())
        val fresh = mapOf("bundle-clip.absproj" to 5L)
        assertEquals(fresh, cache.counts { fresh })
        assertEquals(fresh, CommunityCountsCache(file) { 1000L }.counts { throw AssertionError("disk cache missed") })
        assertTrue(CommunityCountsCache(File(tmp.root, "other.json")) { 1000L }.counts { throw java.net.SocketTimeoutException() }.isEmpty())
        assertTrue(File(tmp.root, "other.json").exists())
    }

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
                  "name": "future-item",
                  "title": "Future Item",
                  "creator": "Carol",
                  "description": "Ignored kind",
                  "tags": [],
                  "added": "2026-04-03",
                  "file": "tools/future-item/item.zip",
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

    @Test fun parseCommunityIndexReadsEffectsWithNoThumbnail() {
        fun item(name: String, extension: String, bytes: Int) =
            """{"kind":"effect","name":"$name","title":"Glow","creator":"Alice","description":"Soft colour","tags":["light"],"file":"effects/$name/item.$extension","bytes":$bytes,"sha256":"$shaA","added":"2026-04-03","thumbnail":"../ignored.webp"}"""
        val entries = listOf(item("glow", "abmfx", 512 * 1024), item("light", "json", 230),
            item("xml", "xml", 1024), item("large", "abmfx", 512 * 1024 + 1))
        val items = parseCommunityIndex("""{"format":"absentmotion-community","version":2,"items":[${entries.joinToString()}]}""", 52).items
        assertEquals(listOf("glow", "light"), items.map { it.name })
        val effect = items.first()
        assertEquals(CommunityKind.Effect, effect.kind)
        assertEquals("Glow", effect.title)
        assertEquals("Alice", effect.creator)
        assertEquals("Soft colour", effect.description)
        assertEquals(listOf("light"), effect.tags)
        assertEquals("2026-04-03", effect.added)
        assertEquals(shaA, effect.sha256)
        assertNull(effect.thumbnail)
    }

    @Test fun parsesCreditAndUsableContentsAndPicksPacksForTheRequiredVersions() {
        fun entry(id: String, version: String, name: String = "Colour") =
            """{"id":"$id","name":"$name","version":$version}"""
        fun pack(name: String, minVersion: Int, vararg entries: String) =
            """{"kind":"effect","name":"$name","creator":"Alice","credit":"CC BY","file":"effects/$name/pack.abmfx","bytes":20,"sha256":"$shaA","minVersion":$minVersion,"contents":[${entries.joinToString()}]}"""
        val packs = listOf(
            pack("old-colour", 0, entry("alice.colour", "1")),
            pack("both", 0, entry("alice.colour", "2"), entry("bob.glow", "1", "Glow"), entry("wrong", "2"), entry("alice.zero", "0")),
            pack("without-contents", 0, entry("bob.glow", "1.5"), entry("bob.glow", "2", "")),
            pack("future", 53, entry("alice.shadow", "1")),
        )
        val index = parseCommunityIndex("""{"format":"absentmotion-community","version":2,"items":[${packs.joinToString()}]}""", 52)
        assertEquals(listOf("old-colour", "both", "without-contents"), index.items.map { it.name })
        assertEquals("Alice", index.items[1].creator)
        assertEquals("CC BY", index.items[1].credit)
        assertEquals(listOf(CommunityEffectContent("alice.colour", "Colour", 2), CommunityEffectContent("bob.glow", "Glow", 1)), index.items[1].contents)
        assertTrue(index.items.last().contents.isEmpty())
        assertEquals("future", index.unavailableEffects.single().name)
        val colour = AddedEffectUse("user.alice.colour", "Colour", 2, "old-colour")
        val glow = AddedEffectUse("user.bob.glow", "Glow", 1, null)
        val shadow = AddedEffectUse("user.alice.shadow", "Shadow", 1, null)
        assertEquals(mapOf(index.items[1] to listOf(colour, glow)), index.effectPacks(listOf(colour, glow, shadow)))
        assertTrue(index.effectPacks(listOf(colour.copy(version = 3))).isEmpty())
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

    @Test fun aRepeatedKindAndNameIsListedOnce() {
        fun entry(kind: String, name: String, title: String) =
            """{"kind":"$kind","name":"$name","title":"$title","file":"${kind}s/$name/$name.${if (kind == "preset") "xml" else "absproj"}","bytes":10,"sha256":"$shaA"}"""
        val json = """{"format":"absentmotion-community","version":2,"items":[${entry("preset", "glow", "First")},${entry("preset", "glow", "Copy")},${entry("bundle", "glow", "Bundle")}]}"""
        val items = parseCommunityIndex(json, 52).items
        assertEquals(listOf("First", "Bundle"), items.map { it.title })
        val misplaced = entry("preset", "glow", "Misplaced").replace("presets/glow/", "presets/glow-copy/")
        val valid = """{"format":"absentmotion-community","version":2,"items":[$misplaced,${entry("preset", "glow", "First")}]}"""
        assertEquals(listOf("First"), parseCommunityIndex(valid, 52).items.map { it.title })
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
                    "s" to ".rect",
                    "fillImage" to "content://media/external/images/media/42"
                ),
                listOf(XmlNode("shadow"))
            )
        )
        val preset = presetScene(project, layer, "Pulse")
        val xmlFile = tmp.newFile("pulse.xml")
        xmlFile.writeText("\uFEFF" + preset.toXml())
        val access = MediaAccess.forPhone(tmp.root)

        val id = addCommunityPreset(xmlFile, store, access)
        val saved = store.list()
        assertEquals(1, saved.size)
        assertEquals(id, saved[0].id)
        assertEquals("Pulse", saved[0].title)
        assertEquals("blocked-content://media/external/images/media/42", saved[0].layer!!.node["fillImage"])

        val emptyFile = tmp.newFile("empty.xml")
        emptyFile.writeText(Scene.create("Empty", 1080, 1920, 30, 0).toXml())
        assertThrows(IllegalArgumentException::class.java) {
            addCommunityPreset(emptyFile, store, access)
        }

        val bigFile = File(tmp.newFolder("big"), "oversized.xml")
        bigFile.writeBytes(ByteArray((1L shl 20).toInt() + 1))
        assertThrows(IllegalArgumentException::class.java) {
            addCommunityPreset(bigFile, store, access)
        }
    }
    private fun item(title: String = "Été Glow", creator: String = "Álîce", tags: List<String> = listOf("néon", "intro"), added: String = "2026-11-17") =
        CommunityItem(CommunityKind.Bundle, title, title, creator, "Not searched", tags, added, "bundles/item/item.absproj", 100, shaA)

    @Test fun searchMatchesTitleIgnoringCaseAndAccents() {
        assertEquals(1, communityMatches(listOf(item()), "ETE GLOW").size)
        assertTrue(communityMatches(listOf(item()), "unrelated").isEmpty())
    }

    @Test fun searchMatchesCreatorIgnoringCaseAndAccents() {
        assertEquals(1, communityMatches(listOf(item()), "ALICE").size)
    }

    @Test fun searchMatchesTagsIgnoringCaseAndAccents() {
        assertEquals(1, communityMatches(listOf(item()), "NEON").size)
    }

    @Test fun everyWordMustMatchSomeField() {
        assertEquals(1, communityMatches(listOf(item()), "  ete  ALICE\tneon ").size)
        assertTrue(communityMatches(listOf(item()), "ete absent").isEmpty())
        assertTrue(communityMatches(listOf(item()), "Not searched").isEmpty())
    }

    @Test fun blankSearchKeepsAllItems() {
        assertEquals(2, communityMatches(listOf(item(), item("Other")), "  ").size)
    }

    @Test fun includedTagsMustAllBePresent() {
        val both = item(tags = listOf("intro", "neon"))
        val one = item("Other", tags = listOf("intro"))
        assertEquals(listOf(both), communityMatches(listOf(one, both), include = setOf("intro", "neon")))
    }

    @Test fun anyExcludedTagRemovesAnItem() {
        val safe = item(tags = listOf("intro"))
        val blocked = item("Other", tags = listOf("neon"))
        assertEquals(listOf(safe), communityMatches(listOf(blocked, safe), exclude = setOf("neon", "text")))
        assertTrue(communityMatches(listOf(safe), include = setOf("intro"), exclude = setOf("intro")).isEmpty())
    }

    @Test fun queryAndTagFiltersWorkTogether() {
        val wanted = item(tags = listOf("intro"))
        assertEquals(listOf(wanted), communityMatches(listOf(wanted, item("Other")), "alice", include = setOf("intro"), exclude = setOf("néon")))
    }

    @Test fun newestFirstIsTheDefaultAndUnknownDatesAreLast() {
        val old = item("Old", added = "2026-01-01")
        val newest = item("Newest", added = "2026-11-17T12:00:00Z")
        val unknown = item("Unknown", added = "")
        val invalid = item("Invalid", added = "2026-02-30")
        assertEquals(listOf(newest, old, unknown, invalid), communityMatches(listOf(unknown, old, invalid, newest)))
    }

    @Test fun aToZUsesTheChosenLocale() {
        val z = item("Zebra")
        val a = item("Apple")
        val accent = item("Ängel")
        assertEquals(listOf(accent, a, z), communityMatches(listOf(z, a, accent), sort = CommunitySort.Title, locale = java.util.Locale.ENGLISH))
        assertEquals(listOf(a, z, accent), communityMatches(listOf(z, a, accent), sort = CommunitySort.Title, locale = java.util.Locale.forLanguageTag("sv")))
    }

}
