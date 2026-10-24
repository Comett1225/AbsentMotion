package app.absentmotion.fonts

import android.os.Build
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

class FontLibrary(context: Context) {
    companion object {
        const val ANDROID_EMOJI = "androidEmoji"
        private const val EMOJI = "NotoColorEmoji.ttf"
        private const val EMOJI_FLAGS = "NotoColorEmojiFlags.ttf"
    }
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("fonts", Context.MODE_PRIVATE)
    private val imported = File(this.context.filesDir, "fonts/imported").apply { mkdirs() }
    private val cache = File(this.context.filesDir, "fonts/google").apply { mkdirs() }
    private val builtin = File(this.context.filesDir, "fonts/builtin").apply { mkdirs() }
    private val bundledNames: Set<String> by lazy {
        this.context.assets.list("fonts").orEmpty().flatMap { folder ->
            this.context.assets.list("fonts/$folder").orEmpty().filter { it.endsWith(".ttf") }.map { "$folder/$it" }
        }.toSet()
    }
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val fontRevision = MutableStateFlow(0L)
    val revision = fontRevision.asStateFlow()
    private val failed = ConcurrentHashMap<String, Long>()
    private fun read(key: String): List<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())
    val favourites: List<String> get() = read("favourites")
    val recent: List<String> get() {
        val uses = JSONObject(prefs.getString("uses", "{}")!!)
        return read("recent").ifEmpty { listOf(FontName.Default.xml) }.sortedByDescending { uses.optInt(it) }
    }
    fun remember(name: String) {
        val uses = JSONObject(prefs.getString("uses", "{}")!!)
        uses.put(name, uses.optInt(name) + 1)
        prefs.edit().putString("recent", JSONArray((listOf(name) + read("recent")).distinct().take(30)).toString())
            .putString("uses", uses.toString()).apply()
    }
    fun favourite(name: String) {
        val old = favourites
        prefs.edit().putString("favourites", JSONArray(if (name in old) old - name else old + name).toString()).apply()
    }
    var browserCategories: Set<Int>
        get() = prefs.getStringSet("browserCategories", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(value) { prefs.edit().putStringSet("browserCategories", value.map { "$it" }.toSet()).apply() }
    var browserSorting: String
        get() = prefs.getString("browserSorting", "Popular") ?: "Popular"
        set(value) { prefs.edit().putString("browserSorting", value).apply() }
    var browserLanguage: Int
        get() = prefs.getInt("browserScript", 0)
        set(value) { prefs.edit().putInt("browserScript", value).apply() }
    fun deleteImported(name: String) {
        val font = FontName.parse(name)
        if (font.source != "imported") return
        File(imported, File(font.family).name).delete()
        prefs.edit().putString("favourites", JSONArray(favourites - name).toString())
            .putString("recent", JSONArray(read("recent") - name).toString()).apply()
        fontRevision.update { it + 1 }
    }
    fun importedFonts(): List<FontName> = imported.listFiles()?.filter { it.extension.lowercase() in listOf("ttf", "otf", "ttc") }
        ?.sortedByDescending { it.lastModified() }?.map { FontName("imported", it.name) } ?: emptyList()
    private fun cachedFile(name: String): File = File(cache,
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) } + ".ttf")
    private val systemFamilies: Map<String, List<Font>> by lazy {
        if (Build.VERSION.SDK_INT < 29) emptyMap() else SystemFonts.getAvailableFonts().groupBy { font ->
            familyKey(font.file?.nameWithoutExtension?.substringBefore('-') ?: "")
        }
    }
    private fun familyKey(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
    fun systemFont(name: String): Font? {
        if (Build.VERSION.SDK_INT < 29) return null
        val wanted = FontName.parse(name)
        if (wanted.source != "googlefonts") return null
        return systemFamilies[familyKey(wanted.family)]?.minByOrNull { font -> kotlin.math.abs(font.style.weight - wanted.weight) +
            if ((font.style.slant == 1) == wanted.italic) 0 else 1000 }
    }
    fun fontIndex(name: String): Int = if (Build.VERSION.SDK_INT < 29) 0 else systemFont(name)?.takeIf { it.file == localFile(name) }?.ttcIndex ?: 0
    fun systemFamily(name: String): String? = when (FontName.parse(name).family) {
        "Roboto" -> "sans-serif"; "Roboto Condensed" -> "sans-serif-condensed"; "Noto Serif" -> "serif"; "Droid Sans Mono" -> "monospace"; else -> null
    }
    private fun bundledAsset(font: FontName, exact: Boolean = true): String? = BundledFonts.pick(font, bundledNames, exact)
    private val builtinCurrent: Boolean by lazy {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (prefs.getLong("builtinStamp", 0L) != stamp) {
            builtin.listFiles()?.forEach { it.delete() }
            prefs.edit().putLong("builtinStamp", stamp).apply()
        }
        true
    }
    private fun bundledFile(asset: String, folder: String = "fonts"): File? = synchronized(builtin) {
        check(builtinCurrent)
        val target = File(builtin, asset.replace('/', '_'))
        if (!target.isFile || target.length() == 0L) runCatching {
            val temporary = File.createTempFile("builtin-", ".font", builtin)
            try {
                context.assets.open("$folder/$asset").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(target))
            } finally { temporary.delete() }
        }
        target.takeIf { it.isFile && it.length() > 0 }
    }
    fun localFile(name: String): File? {
        val font = FontName.parse(name)
        if (font.source == "imported") return File(imported, File(font.family).name).takeIf { it.isFile }
        bundledAsset(font)?.let { asset -> bundledFile(asset)?.let { return it } }
        cachedFile(name).takeIf { it.isFile }?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) systemFont(name)?.file?.takeIf { it.isFile }?.let { return it }
        val base = when (font.family) { "Roboto" -> "Roboto"; "Noto Serif" -> "NotoSerif"; "Droid Sans Mono" -> "DroidSansMono"; else -> return null }
        val variant = when (font.weight) { 100 -> "Thin"; 200, 300 -> "Light"; 500 -> "Medium"; 600, 700 -> "Bold"; 800, 900 -> "Black"; else -> "Regular" }
        val suffix = if (font.italic) { if (variant == "Regular") "Italic" else "${variant}Italic" } else variant
        return listOf(File("/system/fonts/$base-$suffix.ttf"), File("/system/fonts/$base.ttf"), File("/system/fonts/$base-VariableFont_wght.ttf"))
            .firstOrNull { it.isFile }
    }
    fun renderFile(name: String): File? = localFile(name) ?: bundledAsset(FontName.parse(name), exact = false)?.let { bundledFile(it) }
    fun emojiFile(flags: Boolean = false): File? =
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(ANDROID_EMOJI, false)) bundledFile(if (flags) EMOJI_FLAGS else EMOJI, "emoji") else null
    private val faces = ConcurrentHashMap<String, Pair<String, Typeface>>()
    fun cachedTypeface(name: String): Typeface? = faces[name]?.second
    fun typeface(name: String): Typeface {
        val font = FontName.parse(name)
        val file = renderFile(name)
        val stamp = file?.let { it.path + ":" + it.lastModified() }.orEmpty()
        faces[name]?.takeIf { it.first == stamp }?.let { return it.second }
        val variable = file?.name?.contains("-Variable") == true
        val face = file?.takeUnless { it.path.startsWith("/system/") && systemFamily(name) != null }?.let { runCatching {
            Typeface.Builder(it).setTtcIndex(fontIndex(name)).apply { if (variable) setFontVariationSettings("'wght' ${font.weight}") }.build()
        }.getOrNull() }
            ?: Typeface.create(systemFamily(name) ?: font.family, Typeface.NORMAL)
        return (if (font.source == "imported" || variable) face else Typeface.create(face, font.weight, font.italic)).also { faces[name] = stamp to it }
    }
    suspend fun import(uri: Uri): FontName = withContext(Dispatchers.IO) {
        val filename = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: uri.lastPathSegment ?: "font"
        val raw = try {
            context.contentResolver.openInputStream(uri)?.use { input -> ByteArrayOutputStream().also { copyLimited(input, it) }.toByteArray() }
        } catch (e: FontImportError) { throw e } catch (_: Exception) { null } ?: throw FontImportError(FontProblem.Unreadable)
        val (bytes, kind) = checkedFont(raw)
        imported.listFiles()?.firstOrNull { it.isFile && it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
            ?.let { throw FontImportError(FontProblem.AlreadyImported) }
        val clean = File(filename).name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "font" }
        var destination = File(imported, "$clean.${kind.extension}")
        var number = 1
        while (destination.exists()) destination = File(imported, "$clean-${number++}.${kind.extension}")
        val temporary = File.createTempFile("import-", ".font", cache)
        try {
            temporary.writeBytes(bytes)
            if (Build.VERSION.SDK_INT >= 29) try { Font.Builder(temporary).build() } catch (_: Exception) { throw FontImportError(FontProblem.Corrupted) }
            if (!temporary.renameTo(destination)) throw FontImportError(FontProblem.Unreadable)
            fontRevision.update { it + 1 }
            FontName("imported", destination.name)
        } finally { temporary.delete() }
    }
    private fun checkFont(file: File) = checkNotNull(Typeface.Builder(file).build()) { "Not a font." }
    suspend fun resolve(name: String, download: Boolean = false): File? = withContext(Dispatchers.IO) {
        localFile(name)?.let { return@withContext it }
        val font = FontName.parse(name)
        if (!download || font.source != "googlefonts") return@withContext null
        downloadLocks.getOrPut(name) { Mutex() }.withLock {
            localFile(name)?.let { return@withLock it }
            if (System.currentTimeMillis() - (failed[name] ?: 0L) < 60_000) return@withLock null
            runCatching {
                val css = request("https://fonts.googleapis.com/css2?family=${Uri.encode(font.family)}:ital,wght@${if (font.italic) 1 else 0},${font.weight}") { it.reader().readText() }
                val fontUrl = Regex("url\\((https://fonts\\.gstatic\\.com/[^)]+)\\)").find(css)?.groupValues?.get(1) ?: error("Font unavailable")
                val temporary = File.createTempFile("download-", ".font", cache)
                try {
                    request(fontUrl) { input -> temporary.outputStream().use { copyLimited(input, it) } }
                    checkFont(temporary)
                    val destination = cachedFile(name)
                    check(temporary.renameTo(destination))
                    fontRevision.update { it + 1 }
                    destination
                } finally { temporary.delete() }
            }.onSuccess { failed.remove(name) }.getOrElse { failed[name] = System.currentTimeMillis(); null }
        }
    }
    private fun <T> request(url: String, read: (java.io.InputStream) -> T): T {
        val target = URL(url)
        require(target.protocol == "https" && target.host in listOf("fonts.googleapis.com", "fonts.gstatic.com"))
        val connection = target.openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "AbsentMotion/1.0")
        return try { check(connection.responseCode == 200); connection.inputStream.use(read) } finally { connection.disconnect() }
    }
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream) {
        val bytes = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            total += count
            if (total > MAX_FONT_BYTES) throw FontImportError(FontProblem.TooLarge)
            output.write(bytes, 0, count)
        }
    }
}
