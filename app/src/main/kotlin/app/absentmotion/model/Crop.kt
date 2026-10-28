package app.absentmotion.model

import kotlin.math.max
import kotlin.math.min

const val CROP_ID = "absent.crop"

val CropSides = listOf("left", "top", "right", "bottom")

data class CropBox(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val width: Float get() = 1f - left - right
    val height: Float get() = 1f - top - bottom

    fun side(i: Int): Float = when (i) { 0 -> left; 1 -> top; 2 -> right; else -> bottom }

    fun withSide(i: Int, value: Float): CropBox {
        val v = value.coerceIn(0f, 1f - MIN - side((i + 2) % 4))
        return when (i) { 0 -> copy(left = v); 1 -> copy(top = v); 2 -> copy(right = v); else -> copy(bottom = v) }
    }

    fun moved(du: Float, dv: Float): CropBox {
        val dx = du.coerceIn(-left, right)
        val dy = dv.coerceIn(-top, bottom)
        return CropBox(left + dx, top + dy, right - dx, bottom - dy)
    }

    fun dragged(handle: Int, du: Float, dv: Float, ratio: Float? = null): CropBox {
        val movesLeft = handle == 0 || handle == 3 || handle == 7
        val movesRight = handle == 1 || handle == 2 || handle == 5
        val movesTop = handle == 0 || handle == 1 || handle == 4
        val movesBottom = handle == 2 || handle == 3 || handle == 6
        var x0 = left
        var x1 = 1f - right
        var y0 = top
        var y1 = 1f - bottom
        if (movesLeft) x0 = (x0 + du).coerceIn(0f, x1 - MIN)
        if (movesRight) x1 = (x1 + du).coerceIn(x0 + MIN, 1f)
        if (movesTop) y0 = (y0 + dv).coerceIn(0f, y1 - MIN)
        if (movesBottom) y1 = (y1 + dv).coerceIn(y0 + MIN, 1f)
        if (ratio == null || ratio <= 0f) return CropBox(x0, y0, 1f - x1, 1f - y1)
        val horizontal = movesLeft || movesRight
        val vertical = movesTop || movesBottom
        var w = x1 - x0
        var h = y1 - y0
        val maxW = when { movesLeft -> x1; movesRight -> 1f - x0; else -> 1f }
        val maxH = when { movesTop -> y1; movesBottom -> 1f - y0; else -> 1f }
        if (horizontal && vertical) {
            if (w / ratio > h) h = w / ratio else w = h * ratio
        } else if (horizontal) h = w / ratio else w = h * ratio
        if (w < MIN) { w = MIN; h = w / ratio }
        if (h < MIN) { h = MIN; w = h * ratio }
        if (w > maxW) { w = maxW; h = w / ratio }
        if (h > maxH) { h = maxH; w = h * ratio }
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        x0 = when { movesLeft -> x1 - w; movesRight -> x0; else -> (cx - w / 2f).coerceIn(0f, 1f - w) }
        y0 = when { movesTop -> y1 - h; movesBottom -> y0; else -> (cy - h / 2f).coerceIn(0f, 1f - h) }
        return CropBox(x0, y0, 1f - x0 - w, 1f - y0 - h).clamped()
    }

    fun clamped(): CropBox {
        val l = left.coerceIn(0f, 1f - MIN)
        val t = top.coerceIn(0f, 1f - MIN)
        return CropBox(l, t, right.coerceIn(0f, 1f - MIN - l), bottom.coerceIn(0f, 1f - MIN - t))
    }

    companion object {
        const val MIN = 0.01f

        fun fit(ratio: Float): CropBox {
            val w = min(1f, ratio)
            val h = min(1f, 1f / ratio)
            return CropBox((1f - w) / 2f, (1f - h) / 2f, (1f - w) / 2f, (1f - h) / 2f)
        }
    }
}

fun cropRatio(visual: Float, layerWidth: Float, layerHeight: Float): Float = visual * max(layerHeight, 1e-3f) / max(layerWidth, 1e-3f)

val Layer.cropIndex: Int get() = effects.indexOfFirst { it["id"] == CROP_ID }

fun Layer.cropBox(index: Int = cropIndex): CropBox {
    val effect = effects.getOrNull(index) ?: return CropBox()
    val v = CropSides.map { (effect.effectProperty(it)?.valueAt(t) ?: "0").finiteFloat() ?: 0f }
    return CropBox(v[0], v[1], v[2], v[3]).clamped()
}

fun Layer.withCropBox(index: Int, box: CropBox): Layer {
    val shown = box.clamped()
    return withEffect(index) { e ->
        CropSides.foldIndexed(e) { i, node, side -> node.withEffectProperty(side, "float", formatFloats(floatArrayOf(shown.side(i))), t, snap) }
    }
}

fun Layer.onCropKeyframe(index: Int): Boolean = effects.getOrNull(index)?.effectProperty("left")?.let { it.keyframeAt(snap) >= 0 } == true

fun Layer.withCropKeyframes(index: Int): Layer {
    val on = onCropKeyframe(index)
    val box = cropBox(index)
    return withEffect(index) { e ->
        CropSides.foldIndexed(e) { i, node, side ->
            val old = node.effectProperty(side)
            val property = old ?: XmlNode("property", mapOf("name" to side, "type" to "float", "value" to formatFloats(floatArrayOf(box.side(i)))))
            val next = if ((property.keyframeAt(snap) >= 0) == on) property.withKeyframeToggled(t, snap) else property
            node.copy(children = if (old == null) node.children + next else node.children.map { if (it === old) next else it })
        }
    }
}
