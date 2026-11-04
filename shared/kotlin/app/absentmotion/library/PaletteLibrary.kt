package app.absentmotion.library

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.R
import app.absentmotion.effects.Palettes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

enum class PaletteSort { Hue, Lightness, Saturation }

data class ColourPalette(
    val id: String,
    val name: String,
    val colors: List<Int>,
    val locked: Boolean = false,
    val builtIn: Boolean = false,
    @param:StringRes val title: Int = 0,
) {
    val editable get() = !builtIn
    val open get() = !builtIn && !locked
    val removable get() = open

    fun added(rgb: Int) = if (open && colors.size < Palettes.MAX_COLORS) copy(colors = colors + (rgb and 0xFFFFFF)) else this

    fun deleted(index: Int) = if (open && index in colors.indices) copy(colors = colors.filterIndexed { i, _ -> i != index }) else this

    fun duplicated(index: Int) =
        if (open && index in colors.indices && colors.size < Palettes.MAX_COLORS) copy(colors = colors.take(index + 1) + colors[index] + colors.drop(index + 1)) else this

    fun sorted(by: PaletteSort) = if (editable) copy(colors = sortColors(colors, by)) else this

    fun renamed(text: String) = if (editable) copy(name = text.replace('\n', ' ').take(MAX_NAME)) else this

    fun locking(on: Boolean) = if (editable) copy(locked = on) else this

    companion object {
        const val MAX_NAME = 60
    }
}

fun sortColors(colors: List<Int>, by: PaletteSort): List<Int> {
    fun parts(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f
        val g = (c shr 8 and 255) / 255f
        val b = (c and 255) / 255f
        val max = maxOf(r, g, b)
        val chroma = max - minOf(r, g, b)
        val hue = when {
            chroma == 0f -> -1f
            max == r -> ((g - b) / chroma).mod(6f) * 60f
            max == g -> ((b - r) / chroma + 2f) * 60f
            else -> ((r - g) / chroma + 4f) * 60f
        }
        return floatArrayOf(hue, if (max == 0f) 0f else chroma / max, 0.299f * r + 0.587f * g + 0.114f * b)
    }
    val keyed = colors.map { it to parts(it) }
    val order: Comparator<Pair<Int, FloatArray>> = when (by) {
        PaletteSort.Hue -> compareBy({ it.second[0] }, { it.second[2] })
        PaletteSort.Lightness -> compareBy({ it.second[2] }, { it.second[0] })
        PaletteSort.Saturation -> compareBy({ it.second[1] }, { it.second[2] })
    }
    return keyed.sortedWith(order).map { it.first }
}

private fun hex(text: String) = text.split(' ').map { it.toInt(16) }

val BuiltInPalettes: List<ColourPalette> = listOf(
    ColourPalette(PaletteLibrary.DEFAULT, "", hex("e5484d f08a24 f5d90a 30a46c 12a594 3e63dd 8e4ec6 ffffff c9ccd1 8b8f97 4a4d55 000000 e93d82 7ce2fe"), true, true, R.string.palette_default),
    ColourPalette("builtin.pastel", "", hex("ffb3ba ffdfba ffffba baffc9 bae1ff d7baff ffc8dd cdeac0 fde4cf e2ece9 f1c0e8 a3c4f3"), true, true, R.string.palette_pastel),
    ColourPalette("builtin.neon", "", hex("ff073a ff6ec7 fe019a bc13fe 4d4dff 04d9ff 39ff14 ccff00 ffff33 ff9933"), true, true, R.string.palette_neon),
    ColourPalette("builtin.earth", "", hex("3b2f2f 5c4033 8b5a2b a0522d c19a6b d2b48c e3c16f 808000 6b8e23 556b2f"), true, true, R.string.palette_earth),
    ColourPalette("builtin.skin", "", hex("3d2314 5c3a21 8d5524 a5694f c68642 d39a6a e0ac69 f1c27d f3d4c2 ffdbac"), true, true, R.string.palette_skin),
    ColourPalette("builtin.grays", "", hex("000000 1a1a1a 333333 4d4d4d 666666 808080 999999 b3b3b3 cccccc e6e6e6 ffffff"), true, true, R.string.palette_grays),
) + Palettes.presets.filter { it.name == "PICO-8" || it.name == "Game Boy" }.map {
    ColourPalette("builtin." + it.name.lowercase(Locale.US), it.name, it.colors, locked = true, builtIn = true)
}

class PaletteLibrary(private val file: File) {
    var user by mutableStateOf(emptyList<ColourPalette>())
        private set
    var chosenId by mutableStateOf(DEFAULT)
        private set

    init {
        if (file.exists()) runCatching { parse(file.readText()) }.getOrNull()?.let { (found, chosen) ->
            user = found
            chosenId = chosen?.takeIf { id -> get(id) != null } ?: DEFAULT
        } ?: file.renameTo(File(file.path + ".unreadable"))
    }

    val all get() = BuiltInPalettes.take(1) + user + BuiltInPalettes.drop(1)
    val chosen get() = get(chosenId) ?: BuiltInPalettes.first()

    operator fun get(id: String): ColourPalette? = BuiltInPalettes.firstOrNull { it.id == id } ?: user.firstOrNull { it.id == id }

    fun choose(id: String) {
        if (get(id) == null || id == chosenId) return
        chosenId = id
        save()
    }

    fun put(palette: ColourPalette): Boolean {
        if (palette.builtIn || BuiltInPalettes.any { it.id == palette.id }) return false
        user = if (user.any { it.id == palette.id }) user.map { if (it.id == palette.id) palette else it } else user + palette
        save()
        return true
    }

    fun create(name: String, colors: List<Int>): ColourPalette {
        val made = ColourPalette(UUID.randomUUID().toString(), name.take(ColourPalette.MAX_NAME), colors.map { it and 0xFFFFFF }.take(Palettes.MAX_COLORS))
        put(made)
        return made
    }

    fun remove(id: String): Boolean {
        val palette = user.firstOrNull { it.id == id }?.takeIf { it.removable } ?: return false
        user = user - palette
        if (chosenId == id) chosenId = DEFAULT
        save()
        return true
    }

    fun merge(text: String): Int {
        val known = all.map { it.id }.toSet()
        val fresh = runCatching { parse(text).first }.getOrDefault(emptyList()).filter { it.id !in known }
        if (fresh.isEmpty()) return 0
        user = user + fresh
        save()
        return fresh.size
    }

    fun toJson(): String = JSONObject().put("version", 1).put("chosen", chosenId).put("palettes", JSONArray().apply {
        for (p in user) put(JSONObject().put("id", p.id).put("name", p.name).put("locked", p.locked)
            .put("colors", JSONArray(p.colors.map { String.format(Locale.US, "#%06x", it and 0xFFFFFF) })))
    }).toString(1)

    private fun save() = runCatching { writeAtomically(file, toJson(), sync = false) }

    companion object {
        const val FILE = "palettes.json"
        const val DEFAULT = "builtin.default"
        private val opened = HashMap<String, PaletteLibrary>()

        fun at(root: File): PaletteLibrary = synchronized(opened) { opened.getOrPut(root.absolutePath) { PaletteLibrary(File(root, FILE)) } }

        fun parse(text: String): Pair<List<ColourPalette>, String?> {
            val json = JSONObject(text)
            val list = json.optJSONArray("palettes") ?: JSONArray()
            val builtIn = BuiltInPalettes.map { it.id }.toSet()
            val found = (0 until list.length()).mapNotNull { i ->
                val p = list.optJSONObject(i) ?: return@mapNotNull null
                val id = p.optString("id").takeIf { it.isNotBlank() && it !in builtIn } ?: return@mapNotNull null
                val colors = p.optJSONArray("colors") ?: JSONArray()
                ColourPalette(
                    id, p.optString("name").take(ColourPalette.MAX_NAME),
                    Palettes.read((0 until colors.length()).joinToString(" ") { colors.optString(it) }).take(Palettes.MAX_COLORS),
                    p.optBoolean("locked"),
                )
            }.distinctBy { it.id }
            return found to json.optString("chosen").takeIf { it.isNotBlank() }
        }
    }
}
