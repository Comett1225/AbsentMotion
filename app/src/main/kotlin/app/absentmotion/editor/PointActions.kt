package app.absentmotion.editor

import app.absentmotion.model.Contour
import app.absentmotion.model.Handle
import app.absentmotion.model.Knot
import app.absentmotion.model.Pt
import app.absentmotion.model.contours
import app.absentmotion.model.shapeKeys
import app.absentmotion.model.withContoursAt
import app.absentmotion.model.withEveryShape
import app.absentmotion.model.withShapeKeyToggled
import app.absentmotion.model.withHandleMoved
import app.absentmotion.model.withPointAppended
import app.absentmotion.model.withPointCurveToggled
import app.absentmotion.model.withPointInserted
import app.absentmotion.model.withPointMoved
import app.absentmotion.model.withPointRemoved

fun EditorState.pointContours(mode: PointMode): List<Contour> =
    (scene.layerAt(mode.layerId, sampleMs)?.contours ?: emptyList()).withEmptyTail(mode.contour)

private fun EditorState.change(mode: PointMode, key: Any? = null, change: (List<Contour>) -> List<Contour>) {
    edit(key) { s -> s.update(mode.layerId, sampleMs) { l -> l.withContoursAt(change((l.contours ?: emptyList()).withEmptyTail(mode.contour))) } }
}

private fun EditorState.reshape(mode: PointMode, change: (List<Contour>) -> List<Contour>) {
    edit { s -> s.update(mode.layerId, sampleMs) { l -> l.withEveryShape { change(it.withEmptyTail(mode.contour)) } } }
}

fun EditorState.toggleShapeKey(mode: PointMode) {
    edit { s -> s.update(mode.layerId, sampleMs) { it.withShapeKeyToggled() } }
}

fun EditorState.shapeKeyFrames(mode: PointMode): List<Int> {
    val layer = scene.layer(mode.layerId) ?: return emptyList()
    return layer.shapeKeys.map { scene.frameAt(layer.timeOf(it.t)) }.distinct()
}

fun EditorState.onionShape(mode: PointMode): List<Contour>? {
    val layer = scene.layerAt(mode.layerId, sampleMs) ?: return null
    return layer.shapeKeys.lastOrNull { it.t < layer.t - layer.snap }?.contours
}

private fun <T> List<T>.replaced(index: Int, value: T) = mapIndexed { i, v -> if (i == index) value else v }

fun Knot.at(handle: Handle): Pt? = when (handle) {
    Handle.In -> inHandle
    Handle.Out -> outHandle
    else -> p
}

fun EditorState.moveSelectedTo(mode: PointMode, to: Pt, key: Any? = "point-drag") {
    if (mode.handle == Handle.Add) return
    change(mode, key) { list ->
        val c = list.getOrNull(mode.contour) ?: return@change list
        val k = c.knots.getOrNull(mode.point) ?: return@change list
        val moved = when (mode.handle) {
            Handle.Main -> c.withPointMoved(mode.point, to - k.p)
            else -> c.withHandleMoved(mode.point, mode.handle, to, mode.angleLock, mode.distanceLock)
        }
        list.replaced(mode.contour, moved)
    }
}

fun EditorState.toggleCurve(mode: PointMode) {
    if (mode.handle == Handle.Add) return
    change(mode) { list ->
        val c = list.getOrNull(mode.contour)?.takeIf { mode.point in it.knots.indices } ?: return@change list
        list.replaced(mode.contour, c.withPointCurveToggled(mode.point))
    }
    mode.handle = Handle.Main
}

fun EditorState.toggleClosed(mode: PointMode) {
    reshape(mode) { list ->
        val c = list.getOrNull(mode.contour) ?: return@reshape list
        if (!c.canToggleClosed) return@reshape list
        list.replaced(mode.contour, c.copy(closed = !c.closed))
    }
}

val Contour.canToggleClosed: Boolean get() = knots.size >= 2

fun EditorState.deletePoint(mode: PointMode) {
    var selectContour = mode.contour
    var selectPoint = mode.point
    reshape(mode) { list ->
        val c = list.getOrNull(mode.contour)?.takeIf { mode.point in it.knots.indices } ?: return@reshape list
        val removed = c.withPointRemoved(mode.point)
        if (removed.knots.isEmpty() && list.size > 1) {
            selectContour = (mode.contour - 1).coerceAtLeast(0)
            selectPoint = (list.getOrNull(selectContour)?.knots?.lastIndex ?: -1).coerceAtLeast(0)
            list.filterIndexed { i, _ -> i != mode.contour }
        } else {
            selectPoint = (mode.point - 1).coerceAtLeast(0).coerceAtMost(removed.knots.lastIndex)
            list.replaced(mode.contour, removed)
        }
    }
    mode.contour = selectContour
    mode.point = selectPoint
    mode.handle = Handle.Main
    if (pointContours(mode).getOrNull(mode.contour)?.knots.isNullOrEmpty()) selectAdd(mode, mode.contour, -1)
}

fun Contour.endsAt(point: Int): Boolean = !closed && (knots.isEmpty() || point == knots.lastIndex)

fun EditorState.selectAdd(mode: PointMode, contour: Int, point: Int) {
    mode.contour = contour
    mode.point = point
    mode.handle = Handle.Add
    mode.curveAt = 0.5f
    val c = pointContours(mode).getOrNull(contour)
    mode.ghost = c?.takeIf { it.endsAt(point) }?.let { (it.knots.lastOrNull()?.p ?: Pt(0f, 0f)) + Pt(60f, 0f) }
}

fun EditorState.placePoint(mode: PointMode, at: Pt? = null, pull: Pt? = null) {
    val list = pointContours(mode)
    val c = list.getOrNull(mode.contour) ?: return
    if (c.endsAt(mode.point)) {
        val position = at ?: mode.ghost ?: return
        val anchor = c.knots.lastOrNull()?.p
        reshape(mode) { l ->
            val own = l[mode.contour]
            val last = own.knots.lastOrNull()?.p
            l.replaced(mode.contour, own.withPointAppended(if (anchor == null || last == null) position else last + (position - anchor), pull))
        }
        selectAdd(mode, mode.contour, c.knots.size)
    } else if (mode.point in c.knots.indices && c.knots.size > 1) {
        reshape(mode) { l -> l.replaced(mode.contour, l[mode.contour].let { own -> if (mode.point in own.knots.indices && own.knots.size > 1) own.withPointInserted(mode.point, mode.curveAt) else own }) }
        mode.point += 1
        mode.handle = Handle.Main
    }
}

fun EditorState.gotoContour(mode: PointMode, step: Int) {
    val list = pointContours(mode)
    val next = mode.contour + step
    if (next < 0) return
    if (next >= list.size) {
        if (list.last().knots.isEmpty()) return
        mode.contour = list.size
        mode.point = -1
        mode.handle = Handle.Add
        mode.ghost = Pt(0f, 0f)
        return
    }
    mode.contour = next
    val c = list[next]
    if (c.knots.isEmpty()) { mode.point = -1; mode.handle = Handle.Add } else { mode.point = 0; mode.handle = Handle.Main }
}
