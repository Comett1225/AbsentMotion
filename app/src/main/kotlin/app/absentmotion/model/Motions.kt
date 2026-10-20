package app.absentmotion.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MotionSlot(val xml: String, val attr: String) { In("in", "genIn"), Out("out", "genOut"), Loop("loop", "genLoop") }

data class MotionRecord(val transition: Boolean, val id: String, val ms: Int, val extend: Int = 0, val shift: Int = 0, val partner: Long = 0) {
    override fun toString(): String = if (transition) "t $id $ms $extend $shift $partner" else "a $id $ms"

    companion object {
        fun parse(text: String?): MotionRecord? {
            val p = text?.trim()?.split(Regex("\\s+")) ?: return null
            val ms = p.getOrNull(2)?.toIntOrNull() ?: return null
            return when (p[0]) {
                "a" -> MotionRecord(false, p[1], ms)
                "t" -> MotionRecord(true, p[1], ms, p.getOrNull(3)?.toIntOrNull() ?: 0, p.getOrNull(4)?.toIntOrNull() ?: 0, p.getOrNull(5)?.toLongOrNull() ?: 0)
                else -> null
            }
        }
    }
}

fun Layer.motion(slot: MotionSlot): MotionRecord? = MotionRecord.parse(node[slot.attr])

class Look(val prop: String, val away: (rest: FloatArray, w: Float, h: Float, sign: Float) -> FloatArray)

class Fx(val id: String, val keyed: Map<String, Pair<Float, Float>>, val fixed: Map<String, String> = emptyMap())

class Motion(val id: String, val label: String, val ease: Easing, val looks: List<Look> = emptyList(), val fx: List<Fx> = emptyList()) {
    fun curve(inSide: Boolean): Easing = if (inSide) ease else ease.reversed() ?: ease
}

enum class Dir(val x: Float, val y: Float) { Left(-1f, 0f), Right(1f, 0f), Up(0f, -1f), Down(0f, 1f) }

private val EaseOut = Easing("cubicBezier", listOf(0.22f, 1f, 0.36f, 1f))
private val EaseInOut = Easing("cubicBezier", listOf(0.42f, 0f, 0.58f, 1f))
private val Overshoot = Easing("cubicBezier", listOf(0.34f, 1.56f, 0.64f, 1f))
private val Whip = Easing("cubicBezier", listOf(0.8f, 0f, 0.2f, 1f))
private val Bouncy = Easing("bounce", listOf(0.4f, 0.5f))

val Fade = Look("opacity") { _, _, _, _ -> floatArrayOf(0f) }
fun slide(dir: Dir) = Look("location") { r, w, h, s -> r.copyOf().also { it[0] += s * dir.x * w; it[1] += s * dir.y * h } }
fun scaled(inK: Float, outK: Float) = Look("scale") { r, _, _, s -> FloatArray(r.size) { r[it] * if (s < 0) inK else outK } }
fun turn(degrees: Float) = Look("rotation") { r, _, _, s -> floatArrayOf(r[0] + s * degrees) }
private val Drop = Look("location") { r, _, h, _ -> r.copyOf().also { it[1] -= 0.6f * h } }

private fun number(v: Float) = formatFloats(floatArrayOf(v))
private fun overlay(color: String) = Fx("absent.solid", mapOf("amount" to (0f to 1f)), mapOf("color" to color, "blend" to number(0f)))
private val BlurFx = Fx("absent.blur", mapOf("strength" to (0f to 60f)))

object Animations {
    val InOut: List<Motion> = listOf(
        Motion("fade", "Fade", EaseOut, listOf(Fade)),
        Motion("slide-left", "Slide left", EaseOut, listOf(slide(Dir.Left))),
        Motion("slide-right", "Slide right", EaseOut, listOf(slide(Dir.Right))),
        Motion("slide-up", "Slide up", EaseOut, listOf(slide(Dir.Up))),
        Motion("slide-down", "Slide down", EaseOut, listOf(slide(Dir.Down))),
        Motion("zoom-in", "Zoom in", EaseOut, listOf(scaled(0.4f, 1.8f), Fade)),
        Motion("zoom-out", "Zoom out", EaseOut, listOf(scaled(1.8f, 0.4f), Fade)),
        Motion("pop", "Pop", Overshoot, listOf(scaled(0.01f, 0.01f))),
        Motion("bounce", "Bounce", Bouncy, listOf(Drop)),
        Motion("spin", "Spin", EaseOut, listOf(turn(360f), scaled(0.3f, 0.3f), Fade)),
        Motion("blur", "Blur", EaseOut, listOf(Fade), listOf(BlurFx)),
    )

    val Loops: List<Pair<String, String>> = listOf("pulse" to "Pulse", "wobble" to "Wobble", "float" to "Float", "rotate" to "Rotate")

    fun find(id: String?): Motion? = InOut.firstOrNull { it.id == id }

    fun loop(id: String, ms: Int, h: Float): Fx? {
        val hz = 1000f / ms
        return when (id) {
            "pulse" -> Fx("absent.pulsesize", emptyMap(), mapOf("amount" to number(0.08f), "frequency" to number(hz)))
            "wobble" -> Fx("absent.swing", emptyMap(), mapOf("angle" to number(6f), "frequency" to number(hz)))
            "float" -> Fx("absent.oscillate", emptyMap(), mapOf("angle" to number(90f), "magnitude" to number(0.02f * h), "frequency" to number(hz)))
            "rotate" -> Fx("absent.spin", emptyMap(), mapOf("speed" to number(360f * hz)))
            else -> null
        }
    }
}

enum class TransitionKind(val id: String, val label: String, val overlap: Boolean, val dir: Dir = Dir.Left) {
    CrossDissolve("cross-dissolve", "Cross dissolve", true), DipBlack("dip-black", "Dip to black", false), DipWhite("dip-white", "Dip to white", false),
    SlideLeft("slide-left", "Slide left", true, Dir.Left), SlideRight("slide-right", "Slide right", true, Dir.Right),
    SlideUp("slide-up", "Slide up", true, Dir.Up), SlideDown("slide-down", "Slide down", true, Dir.Down),
    PushLeft("push-left", "Push left", true, Dir.Left), PushRight("push-right", "Push right", true, Dir.Right),
    PushUp("push-up", "Push up", true, Dir.Up), PushDown("push-down", "Push down", true, Dir.Down),
    ZoomIn("zoom-in", "Zoom in", true), ZoomOut("zoom-out", "Zoom out", true), Spin("spin", "Spin", true), Blur("blur", "Blur", true),
    WhipPan("whip-pan", "Whip pan", true), Glitch("glitch", "Glitch", false), Flash("flash", "Flash", false), Shake("shake", "Shake", false);

    fun sides(bOnTop: Boolean, bothFade: Boolean): Pair<Motion, Motion> {
        val fadeA = if (!bOnTop || bothFade) listOf(Fade) else emptyList()
        val fadeB = if (bOnTop || bothFade) listOf(Fade) else emptyList()
        fun pair(ease: Easing, a: List<Look>, b: List<Look> = a, fx: List<Fx> = emptyList()) =
            Motion(id, label, ease, a, fx) to Motion(id, label, ease, b, fx)
        return when (this) {
            CrossDissolve -> pair(EaseInOut, fadeA, fadeB)
            DipBlack -> pair(EaseInOut, emptyList(), fx = listOf(overlay(formatColor(0xFF000000.toInt()))))
            DipWhite -> pair(EaseInOut, emptyList(), fx = listOf(overlay(formatColor(0xFFFFFFFF.toInt()))))
            SlideLeft, SlideRight, SlideUp, SlideDown ->
                if (bOnTop) pair(EaseInOut, emptyList(), listOf(slide(dir))) else pair(EaseInOut, listOf(slide(dir)), emptyList())
            PushLeft, PushRight, PushUp, PushDown -> pair(EaseInOut, listOf(slide(dir)))
            ZoomIn -> pair(EaseInOut, listOf(scaled(0.5f, 2f)) + fadeA, listOf(scaled(0.5f, 2f)) + fadeB)
            ZoomOut -> pair(EaseInOut, listOf(scaled(2f, 0.5f)) + fadeA, listOf(scaled(2f, 0.5f)) + fadeB)
            Spin -> pair(EaseInOut, listOf(turn(180f), scaled(0.5f, 0.5f)) + fadeA, listOf(turn(180f), scaled(0.5f, 0.5f)) + fadeB)
            Blur -> pair(EaseInOut, fadeA, fadeB, listOf(BlurFx))
            WhipPan -> pair(Whip, listOf(slide(dir)), fx = listOf(Fx("absent.motionblur", mapOf("length" to (0f to 300f)), mapOf("angle" to number(0f)))))
            Glitch -> pair(EaseOut, emptyList(), fx = listOf(Fx("absent.glitch", mapOf("intensity" to (0f to 1f))), Fx("absent.rgbsplit", mapOf("splitDistance" to (0f to 40f)))))
            Flash -> pair(EaseOut, emptyList(), fx = listOf(Fx("absent.exposure", mapOf("stops" to (0f to 4f)))))
            Shake -> pair(EaseOut, emptyList(), fx = listOf(Fx("absent.autoshake", mapOf("magnitude" to (0f to 48f), "rotation" to (0f to 4f)), mapOf("frequency" to number(14f)))))
        }
    }

    companion object {
        fun of(id: String?): TransitionKind? = entries.firstOrNull { it.id == id }
    }
}

const val MOTION_MIN_MS = 100
const val MOTION_MAX_MS = 2000
const val LOOP_MIN_MS = 200
const val LOOP_MAX_MS = 4000

private const val EDGE = 1e-4f
private val Rest = mapOf("opacity" to floatArrayOf(1f), "location" to floatArrayOf(0f, 0f, 0f), "scale" to floatArrayOf(1f, 1f), "rotation" to floatArrayOf(0f))
private val NotVisual = setOf("audio", "camera", "nullobj")

val Layer.canAnimate: Boolean get() = kind !in NotVisual

private val Layer.length: Int get() = endTime - startTime

fun Scene.mediaLengthMs(layer: Layer): Int =
    (layer.mediaFill?.uri ?: layer.node["src"])?.let { media(it)?.duration }?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: Int.MAX_VALUE

private fun Fx.node(slot: MotionSlot, t0: Float, t1: Float, ease: String?, inSide: Boolean): XmlNode = XmlNode(
    "effect", linkedMapOf("id" to id, "hidden" to "false", "gen" to slot.xml),
    fixed.map { (name, value) -> XmlNode("property", linkedMapOf("name" to name, "type" to if (value.startsWith("#")) "color" else "float", "value" to value)) } +
        keyed.map { (name, range) ->
            val (first, second) = if (inSide) range.second to range.first else range
            XmlNode("property", linkedMapOf("name" to name, "type" to "float"))
                .withKeyframes(listOf(Keyframe(t0, number(first)), Keyframe(t1, number(second), ease)))
        },
)

private fun Layer.withSide(m: Motion, slot: MotionSlot, inSide: Boolean, from: Int, to: Int, w: Float, h: Float): Layer {
    val span = length.coerceAtLeast(1).toFloat()
    val t0 = ((from - startTime) / span).coerceIn(0f, 1f)
    val t1 = ((to - startTime) / span).coerceIn(t0, 1f)
    val ease = m.curve(inSide).takeUnless { it.isLinear }?.toString()
    val sign = if (inSide) -1f else 1f
    var n = node
    for (look in m.looks) {
        val transform = n.child("transform") ?: XmlNode("transform")
        val element = transform.child(look.prop) ?: XmlNode(look.prop)
        val rest = floats(element.valueAt(if (inSide) t1 else t0), Rest.getValue(look.prop))
        val away = look.away(rest, w, h, sign)
        val (first, second) = if (inSide) away to rest else rest to away
        val kept = element.keyframes.filter { it.gen != null || it.t < t0 - EDGE || it.t > t1 + EDGE }
        val keys = kept + Keyframe(t0, formatFloats(first), gen = slot.xml) + Keyframe(t1, formatFloats(second), ease, slot.xml)
        n = n.withChild("transform", transform.withChild(look.prop, element.withKeyframes(keys)))
    }
    for (fx in m.fx) n = n.copy(children = n.children + fx.node(slot, t0, t1, ease, inSide))
    return copy(node = n)
}

private fun XmlNode.withoutKeys(slot: String): XmlNode {
    val keys = keyframes
    val removed = keys.filter { it.gen == slot }
    if (removed.isEmpty()) return this
    val kept = keys.filter { it.gen != slot }
    if (kept.isNotEmpty()) return withKeyframes(kept)
    val rest = if (slot == MotionSlot.Out.xml) removed.first() else removed.last()
    return copy(children = children.filterNot { it.name == "kf" }).with("value", rest.v)
}

fun Layer.withoutMotion(slot: MotionSlot): Layer {
    val record = motion(slot)
    val transform = node.child("transform")
    var n = node.copy(children = node.children.filterNot { it.name == "effect" && it["gen"] == slot.xml }).with(slot.attr, null)
    if (transform != null) n = n.withChild("transform", transform.copy(children = transform.children.map { it.withoutKeys(slot.xml) }))
    var l = copy(node = n)
    if (record?.transition != true) return l
    if (slot == MotionSlot.Out && record.extend in 1 until l.length) l = l.trimmedTo(l.startTime, l.endTime - record.extend)
    if (slot == MotionSlot.In) {
        if (record.shift > 0) l = l.movedTo(l.startTime + record.shift)
        if (record.extend in 1 until l.length) l = l.trimmedTo(l.startTime + record.extend, l.endTime)
    }
    return l
}

fun Scene.withoutMotion(id: Long, slot: MotionSlot): Scene {
    val record = layer(id)?.motion(slot)
    var s = update(id) { it.withoutMotion(slot) }
    if (record != null && record.transition && slot != MotionSlot.Loop) {
        val other = if (slot == MotionSlot.In) MotionSlot.Out else MotionSlot.In
        val back = s.layer(record.partner)?.motion(other)
        if (back?.transition == true && back.partner == id) s = s.update(record.partner) { it.withoutMotion(other) }
    }
    return s
}

fun Scene.withAnimation(id: Long, slot: MotionSlot, motionId: String?, ms: Int): Scene {
    val s = withoutMotion(id, slot)
    val layer = s.layer(id) ?: return this
    if (motionId == null || !layer.canAnimate) return s
    if (slot == MotionSlot.Loop) {
        val d = ms.coerceIn(LOOP_MIN_MS, LOOP_MAX_MS)
        val fx = Animations.loop(motionId, d, height.toFloat()) ?: return s
        return s.update(id) { it.copy(node = it.node.copy(children = it.node.children + fx.node(slot, 0f, 1f, null, true))
            .with(slot.attr, MotionRecord(false, motionId, d).toString())) }
    }
    val motion = Animations.find(motionId) ?: return s
    val d = ms.coerceIn(MOTION_MIN_MS, MOTION_MAX_MS)
    val span = min(d, layer.length)
    val (from, to) = if (slot == MotionSlot.In) layer.startTime to layer.startTime + span else layer.endTime - span to layer.endTime
    return s.update(id) {
        val done = it.withSide(motion, slot, slot == MotionSlot.In, from, to, width.toFloat(), height.toFloat())
        done.copy(node = done.node.with(slot.attr, MotionRecord(false, motionId, d).toString()))
    }
}

fun Scene.cutBetween(first: Long, second: Long): Pair<Layer, Layer>? {
    val x = layer(first) ?: return null
    val y = layer(second) ?: return null
    val (a, b) = if (x.startTime <= y.startTime) x to y else y to x
    return (a to b).takeIf { a.canAnimate && b.canAnimate && b.startTime > a.startTime && b.endTime > a.endTime }
}

fun Scene.cutAt(timeMs: Int): Pair<Layer, Layer>? {
    val near = 2000 / fps
    val shown = layers.filter { it.canAnimate && !it.hidden }
    var best: Pair<Layer, Layer>? = null
    var bestGap = Int.MAX_VALUE
    for (b in shown) for (a in shown) {
        if (b.startTime <= a.startTime || b.endTime <= a.endTime || b.startTime > a.endTime + near) continue
        if (timeMs < min(b.startTime, a.endTime) - near || timeMs > max(b.startTime, a.endTime) + near) continue
        val gap = abs(2 * timeMs - b.startTime - a.endTime)
        if (gap <= bestGap) { best = a to b; bestGap = gap }
    }
    return best
}

data class TransitionResult(val scene: Scene, val note: String? = null)

private fun seconds(ms: Int) = String.format(Locale.US, "%.1f s", ms / 1000f)

fun Scene.withTransition(aId: Long, bId: Long, kind: TransitionKind?, ms: Int): TransitionResult {
    val s = withoutMotion(aId, MotionSlot.Out).withoutMotion(bId, MotionSlot.In)
    if (kind == null) return TransitionResult(s)
    val start = s.cutBetween(aId, bId) ?: return TransitionResult(this)
    var (a, b) = start
    val bOnTop = s.layers.indexOfFirst { it.id == b.id } > s.layers.indexOfFirst { it.id == a.id }
    val (outSide, inSide) = kind.sides(bOnTop, (if (bOnTop) b else a).mediaFill == null)
    val wanted = ms.coerceIn(MOTION_MIN_MS, MOTION_MAX_MS)
    var extA = 0
    var extB = 0
    var shift = 0
    val d: Int
    val aWindow: Pair<Int, Int>
    val bWindow: Pair<Int, Int>
    if (kind.overlap) {
        d = minOf(wanted, a.length, b.length)
        val overlap = a.endTime - b.startTime
        if (overlap >= d) {
            val from = if (bOnTop) b.startTime else a.endTime - d
            aWindow = from to from + d
        } else {
            val extra = d - overlap
            val mediaA = s.mediaLengthMs(a)
            val mediaB = s.mediaLengthMs(b)
            val roomA = a.trimmedTo(a.startTime, a.endTime + extra, mediaA).endTime - a.endTime
            val roomB = b.startTime - b.trimmedTo(max(0, b.startTime - extra), b.endTime, mediaB).startTime
            var growA = min(roomA, extra / 2)
            val growB = min(roomB, extra - growA)
            growA = min(roomA, extra - growB)
            if (growA > 0) a = a.trimmedTo(a.startTime, a.endTime + growA, mediaA)
            if (growB > 0) b = b.trimmedTo(b.startTime - growB, b.endTime, mediaB)
            extA = a.endTime - start.first.endTime
            extB = start.second.startTime - b.startTime
            shift = max(0, d - (a.endTime - b.startTime))
            if (shift > 0) b = b.movedTo(b.startTime - shift)
            aWindow = b.startTime to a.endTime
        }
        bWindow = aWindow
    } else {
        d = minOf(wanted, 2 * a.length, 2 * b.length)
        val half = d / 2
        val cut = if (b.startTime >= a.endTime) null else if (bOnTop) b.startTime else a.endTime
        aWindow = max(a.startTime, (cut ?: a.endTime) - half) to (cut ?: a.endTime)
        bWindow = (cut ?: b.startTime) to min(b.endTime, (cut ?: b.startTime) + half)
    }
    val w = width.toFloat()
    val h = height.toFloat()
    val outA = a.withSide(outSide, MotionSlot.Out, false, aWindow.first, aWindow.second, w, h)
    val inB = b.withSide(inSide, MotionSlot.In, true, bWindow.first, bWindow.second, w, h)
    val next = s.update(aId) { outA.copy(node = outA.node.with(MotionSlot.Out.attr, MotionRecord(true, kind.id, wanted, extA, 0, bId).toString())) }
        .update(bId) { inB.copy(node = inB.node.with(MotionSlot.In.attr, MotionRecord(true, kind.id, wanted, extB, shift, aId).toString())) }
    val notes = listOfNotNull(
        if (shift > 0) "Moved “${b.label.ifEmpty { "the second clip" }}” ${seconds(shift)} earlier so the clips overlap" else null,
        if (d < wanted) "Shortened to ${seconds(d)} to fit the clips" else null,
    )
    return TransitionResult(next, notes.joinToString(". ").ifEmpty { null })
}
