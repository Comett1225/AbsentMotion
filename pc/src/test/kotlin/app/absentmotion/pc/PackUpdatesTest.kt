package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.library.CommunityFeed
import app.absentmotion.library.PackUpdates
import app.absentmotion.ui.home.LocalCommunity
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

class PackUpdatesTest {
    @Test fun anInstalledOfficialPackIsCheckedOnceADayWithASmallPingAndOffersOnlyChangedEffects() {
        val sha1 = "a".repeat(64)
        val sha2 = "b".repeat(64)
        val packs = """{"packs":{"toji-motion":"$sha2"}}"""
        val index = """{"format":"absentmotion-community","version":2,"items":[{"kind":"effect","name":"toji-motion","title":"Toji Motion","official":true,""" +
            """"file":"effects/toji-motion/toji-motion.abmfx","bytes":100,"sha256":"$sha2","contents":[""" +
            """{"id":"toji-motion.glow","name":"Glow","version":1},{"id":"toji-motion.wave","name":"Wave","version":2},{"id":"toji-motion.new","name":"New","version":1}]}]}"""
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.trimStart('/')
            val body = mapOf("packs.json" to packs, "index.json" to index)[path]?.toByteArray()
            val etag = "\"${path.hashCode()}\""
            asked += path + if (ex.requestHeaders.getFirst("If-None-Match") == etag) " 304" else ""
            ex.responseHeaders.add("ETag", etag)
            when {
                body == null -> ex.sendResponseHeaders(404, -1)
                ex.requestHeaders.getFirst("If-None-Match") == etag -> ex.sendResponseHeaders(304, -1)
                else -> ex.sendResponseHeaders(200, body.size.toLong())
            }
            ex.responseBody.use { if (body != null && ex.responseCode == 200) it.write(body) }
        }
        server.start()
        val before = CommunityFeed.source
        CommunityFeed.source = LocalCommunity("http://127.0.0.1:${server.address.port}/")
        val context = Desk.context
        context.getSharedPreferences("effect_packs", 0).edit().clear().apply()
        java.io.File(context.cacheDir, "http").deleteRecursively()
        fun added(key: String, version: Long) = EffectDefinition("user.$key", key, key, "color", "", emptyList(), addedVersion = version, addedPack = "toji-motion")
        val catalog = EffectCatalog(listOf(added("toji-motion.glow", 1), added("toji-motion.wave", 1)))
        try {
            assertNull(PackUpdates.check(context, catalog, now = 1_000_000_000L))
            assertEquals(emptyList<String>(), asked)
            PackUpdates.remember(context, "toji-motion", sha1)
            val update = PackUpdates.check(context, catalog, now = 1_000_000_000L)!!
            assertEquals(listOf("toji-motion.wave", "toji-motion.new"), update.changed.map { it.id })
            assertEquals(listOf("packs.json", "index.json"), asked.toList())
            assertNull(PackUpdates.check(context, catalog, now = 1_000_000_000L + 60_000L))
            assertEquals(2, asked.size)
            PackUpdates.check(context, catalog, now = 1_100_000_000L)
            assertEquals("packs.json 304", asked[2])
            PackUpdates.skip(context, update.item)
            assertNull(PackUpdates.check(context, catalog, now = 1_200_000_000L))
        } finally {
            CommunityFeed.source = before
            server.stop(0)
        }
    }
}
