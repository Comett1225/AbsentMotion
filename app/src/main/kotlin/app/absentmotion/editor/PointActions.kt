package app.absentmotion.editor

import app.absentmotion.model.Contour
import app.absentmotion.model.Handle
import app.absentmotion.model.Knot
import app.absentmotion.model.Pt
import app.absentmotion.model.contours
import app.absentmotion.model.withContours
import app.absentmotion.model.withHandleMoved
import app.absentmotion.model.withPointAppended
import app.absentmotion.model.withPointCurveToggled
import app.absentmotion.model.withPointInserted
import app.absentmotion.model.withPointMoved
import app.absentmotion.model.withPointRemoved

fun EditorState.pointContours(mode: PointMode): List<Contour> =
    (scene.layer(mode.layerId)?.contours ?: emptyList()).withEmptyTail(mode.contour)

private fun EditorState.change(mode: PointMode, key: Any? = null, change: (List<Contour>) -> List<Contour>) {
    edit(key) { s -> s.update(mode.layerId) { l -> if (l.contours == null) l else l.withContours(change(l.contours!!.withEmptyTail(mode.contour))) } }
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
    change(mode) { list ->
        val c = list.getOrNull(mode.contour) ?: return@change list
        if (!c.canToggleClosed) return@change list
        list.replaced(mode.contour, c.copy(closed = !c.closed))
    }
}

val Contour.canToggleClosed: Boolean get() = knots.size >= 2

fun EditorState.deletePoint(mode: PointMode) {
    var selectContour = mode.contour
    var selectPoint = mode.point
    change(mode) { list ->
        val c = list.getOrNull(mode.contour)?.takeIf { mode.point in it.knots.indices } ?: return@change list
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
        change(mode) { l -> l.replaced(mode.contour, l[mode.contour].withPointAppended(position, pull)) }
        selectAdd(mode, mode.contour, c.knots.size)
    } else if (mode.point in c.knots.indices && c.knots.size > 1) {
        change(mode) { l -> l.replaced(mode.contour, l[mode.contour].withPointInserted(mode.point, mode.curveAt)) }
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
