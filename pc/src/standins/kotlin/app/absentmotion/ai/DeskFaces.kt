package app.absentmotion.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.i18n.AppText
import app.absentmotion.R
import kotlin.math.max
import kotlin.math.min

internal fun desktopFaces(argb: IntArray, width: Int, height: Int, turn: Int): List<FaceBox> {
    val scale = min(1f, 640f / max(width, height))
    val w = max(2, (width * scale).toInt() and 1.inv())
    val h = max(2, (height * scale).toInt() and 1.inv())
    val full = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    val scaled = Bitmap.createScaledBitmap(full, w, h, true)
    if (scaled !== full) full.recycle()
    val upright = if (turn == 0) scaled else Bitmap.createBitmap(scaled, 0, 0, w, h, Matrix().apply { postRotate(turn.toFloat()) }, true).also { scaled.recycle() }
    try {
        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
        val boxes = DesktopNative.detectFaces(pixels, upright.width, upright.height) ?: error(AppText.get(R.string.ai_unable_to_process))
        return boxes.toList().chunked(3).filter { it.size == 3 }.map { box ->
            val (x, y) = uprightToFrame(box[0], box[1], w.toFloat(), h.toFloat(), turn)
            FaceBox(x * width / w, y * height / h, box[2] * width / w)
        }
    } finally { upright.recycle() }
}
