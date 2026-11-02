package app.absentmotion.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import app.absentmotion.BuildConfig

internal class GpuCheck(context: Context) {
    private val prefs = context.getSharedPreferences("gpu_check", Context.MODE_PRIVATE)

    private fun key(gl: Boolean) = "${Build.FINGERPRINT}|${BuildConfig.VERSION_CODE}|${if (gl) "gl" else "vk"}"

    fun knownBad(gl: Boolean): Boolean = prefs.getString(key(gl), null) == "bad"

    fun drawsRight(engine: NativeEngine): Boolean {
        val gl = engine.openGl
        prefs.getString(key(gl), null)?.let { return it == "ok" }
        val good = EngineDebug.failCheck?.let { it != (if (gl) "gl" else "vk") } ?: render(engine)
        prefs.edit().putString(key(gl), if (good) "ok" else "bad").apply()
        if (!good) Log.w("AMEngine", "the ${if (gl) "OpenGL" else "Vulkan"} check picture came out wrong")
        return good
    }

    private fun render(engine: NativeEngine): Boolean = try {
        engine.setScene(SCENE)
        engine.setPicture(URI, quad())
        val px = engine.previewStill(0.0, SIZE, SIZE, false)
        px == null || px.size < SIZE * SIZE * 4 || matches(px)
    } catch (e: Exception) {
        Log.w("AMEngine", "GPU check could not run", e)
        true
    } finally {
        runCatching { engine.setPicture(URI, null) }
        runCatching { engine.setScene(EMPTY) }
    }

    companion object {
        private const val URI = "absent-check://quad"
        private const val SIZE = 64
        private const val EMPTY = """<scene title="check" width="$SIZE" height="$SIZE" exportWidth="$SIZE" exportHeight="$SIZE" fps="30" totalTime="1000" bgcolor="#ff000000"></scene>"""
        private const val SCENE = """<scene title="check" width="$SIZE" height="$SIZE" exportWidth="$SIZE" exportHeight="$SIZE" fps="30" totalTime="1000" bgcolor="#ff000000"><shape id="1" label="p" startTime="0" endTime="1000" fillType="media" mediaFillMode="fill" s=".rect" fillImage="$URI"><transform><location value="32,32,0"/></transform><property name="size" type="vec2" value="32,32"/></shape><shape id="2" label="c" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="32,56,0"/><opacity value="0.5"/></transform><property name="size" type="vec2" value="32,8"/><fillColor value="#ffffffff"/></shape></scene>"""

        private fun quad(): Bitmap {
            val colors = IntArray(SIZE * SIZE) { i ->
                val x = i % SIZE
                val y = i / SIZE
                when {
                    y < SIZE / 2 && x < SIZE / 2 -> 0xffff0000.toInt()
                    y < SIZE / 2 -> 0xff00ff00.toInt()
                    x < SIZE / 2 -> 0xff0000ff.toInt()
                    else -> 0xff000000.toInt()
                }
            }
            return Bitmap.createBitmap(colors, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        }

        internal fun matches(px: ByteArray): Boolean {
            fun at(x: Int, y: Int) = IntArray(3) { c -> px[(y * SIZE + x) * 4 + c].toInt() and 0xff }
            fun near(x: Int, y: Int, vararg want: IntRange) = at(x, y).zip(want).all { (v, r) -> v in r }
            val hi = 200..255
            val lo = 0..55
            val mid = 90..215
            return near(16, 16, hi, lo, lo) && near(48, 16, lo, hi, lo) && near(16, 40, lo, lo, hi) && near(48, 40, lo, lo, lo) &&
                near(16, 58, mid, mid, hi) && near(48, 58, mid, mid, mid)
        }
    }
}
