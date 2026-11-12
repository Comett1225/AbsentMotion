package app.absentmotion.export

import kotlin.math.abs
import kotlin.math.roundToInt

data class ThumbSquare(val left: Float, val top: Float, val side: Float) {
    fun fitted(width: Int, height: Int): ThumbSquare {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val short = minOf(w, h)
        val s = side.coerceIn(short * MinThumbSquare, short)
        return ThumbSquare(left.coerceIn(0f, w - s), top.coerceIn(0f, h - s), s)
    }

    fun moved(dx: Float, dy: Float, width: Int, height: Int): ThumbSquare = copy(left = left + dx, top = top + dy).fitted(width, height)

    fun scaled(factor: Float, width: Int, height: Int): ThumbSquare {
        val short = minOf(width, height).coerceAtLeast(1).toFloat()
        val s = (side * factor).coerceIn(short * MinThumbSquare, short)
        return ThumbSquare(left + (side - s) / 2, top + (side - s) / 2, s).fitted(width, height)
    }

    fun dragged(corner: Int, x: Float, y: Float, width: Int, height: Int): ThumbSquare {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val right = corner == 1 || corner == 2
        val bottom = corner == 2 || corner == 3
        val ax = if (right) left else left + side
        val ay = if (bottom) top else top + side
        val room = minOf(if (right) w - ax else ax, if (bottom) h - ay else ay)
        val s = maxOf(abs(x - ax), abs(y - ay)).coerceIn(minOf(w, h) * MinThumbSquare, maxOf(room, minOf(w, h) * MinThumbSquare))
        return ThumbSquare(if (right) ax else ax - s, if (bottom) ay else ay - s, s).fitted(width, height)
    }

    fun corners(): List<Pair<Float, Float>> = listOf(left to top, left + side to top, left + side to top + side, left to top + side)
}

const val MinThumbSquare = 0.2f

fun CommunityDetails.square(width: Int, height: Int): ThumbSquare {
    val w = width.coerceAtLeast(1).toFloat()
    val h = height.coerceAtLeast(1).toFloat()
    val side = squareSize * minOf(w, h)
    return ThumbSquare(squareX * w - side / 2, squareY * h - side / 2, side).fitted(width, height)
}

fun CommunityDetails.withSquare(square: ThumbSquare, width: Int, height: Int): CommunityDetails {
    val fit = square.fitted(width, height)
    val w = width.coerceAtLeast(1).toFloat()
    val h = height.coerceAtLeast(1).toFloat()
    return copy(squareX = (fit.left + fit.side / 2) / w, squareY = (fit.top + fit.side / 2) / h, squareSize = fit.side / minOf(w, h))
}

fun cropSquare(pixels: ByteArray, width: Int, height: Int, square: ThumbSquare): Pair<ByteArray, Int> {
    val side = square.side.roundToInt().coerceIn(1, minOf(width, height).coerceAtLeast(1))
    val left = square.left.roundToInt().coerceIn(0, (width - side).coerceAtLeast(0))
    val top = square.top.roundToInt().coerceIn(0, (height - side).coerceAtLeast(0))
    val out = ByteArray(side * side * 4)
    for (row in 0 until side) System.arraycopy(pixels, ((top + row) * width + left) * 4, out, row * side * 4, side * 4)
    return out to side
}
