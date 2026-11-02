package app.absentmotion.ai

import android.graphics.Bitmap

internal object DeskAiNative {
    private external fun nativeCutoutPixels(handle: Long, pixels: IntArray, width: Int, height: Int, size: Int): Int

    fun cutout(handle: Long, bitmap: Bitmap, size: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val code = nativeCutoutPixels(handle, pixels, bitmap.width, bitmap.height, size)
        if (code == 0) bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return code
    }
}
