package app.absentmotion.library

import app.absentmotion.effects.AddedEffectPrefix
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.platform.Context
import app.absentmotion.ui.home.HttpCache
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateSource
import org.json.JSONObject
import java.io.File

internal data class PackUpdate(val item: CommunityItem, val changed: List<CommunityEffectContent>)

internal object PackUpdates {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val PACKS_MAX_BYTES = 16 * 1024

    private fun prefs(context: Context) = context.getSharedPreferences("effect_packs", 0)

    fun remember(context: Context, pack: String, sha256: String) {
        prefs(context).edit().putString("sha_$pack", sha256).apply()
    }

    fun skip(context: Context, item: CommunityItem) {
        prefs(context).edit().putString("skip_${item.name}", item.sha256).apply()
    }

    fun check(
        context: Context,
        catalog: EffectCatalog,
        now: Long = System.currentTimeMillis(),
        http: (UpdateSource) -> TrustedHttp = { TrustedHttp(it) },
    ): PackUpdate? {
        val prefs = prefs(context)
        val known = catalog.definitions.mapNotNull { it.addedPack }.toSet()
            .mapNotNull { name -> prefs.getString("sha_$name", null)?.let { name to it } }.toMap()
        if (known.isEmpty() || now - prefs.getLong("checked_at", 0L) < DAY_MS) return null
        val source = CommunityFeed.source
        val remote = HttpCache(File(context.cacheDir, "http")).get(http(source), source.asset("", "packs.json"), PACKS_MAX_BYTES)
            ?.let { runCatching { JSONObject(it.decodeToString()).optJSONObject("packs") }.getOrNull() } ?: return null
        prefs.edit().putLong("checked_at", now).apply()
        val moved = known.filter { (name, sha) ->
            val latest = remote.optString(name, "")
            latest.isNotEmpty() && latest != sha && latest != prefs.getString("skip_$name", null)
        }
        if (moved.isEmpty()) return null
        val index = CommunityClient(context.cacheDir, http).index()
        for (name in moved.keys) {
            val item = index.items.firstOrNull { it.kind == CommunityKind.Effect && it.name == name && it.official } ?: continue
            val changed = item.contents.filter { content -> (catalog.find(AddedEffectPrefix + content.id)?.addedVersion ?: 0L) < content.version }
            if (changed.isEmpty()) remember(context, name, item.sha256) else return PackUpdate(item, changed)
        }
        return null
    }
}
