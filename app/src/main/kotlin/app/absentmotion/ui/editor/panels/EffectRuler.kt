package app.absentmotion.ui.editor.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import app.absentmotion.editor.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import app.absentmotion.ui.theme.Am

@Composable
internal fun EffectRuler(
    value: Float, step: Float, min: Float, max: Float, enabled: Boolean,
    onStart: () -> Unit, onValue: (Float) -> Unit, onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    AndroidView(modifier = modifier, factory = { EffectRulerView(it, scope) },
        onRelease = { it.stop() },
        update = { it.configure(value, step, min, max, enabled, onStart, onValue, onEnd) })
}

private class EffectRulerView(context: Context, private val scope: CoroutineScope) : View(context) {
    private val density = resources.displayMetrics.density
    private val spacing = (5.5f * density).toInt().coerceAtLeast(1)
    private val tickWidth = (.5f * density).toInt().coerceAtLeast(1).toFloat()
    private val needleWidth = (1.5f * density).toInt().coerceAtLeast(1).toFloat()
    private val inset = 8f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val scroller = OverScroller(context)
    private var fling: Job? = null
    private var tracking = false
    private var position = 0f
    private var lastTick = 0
    private var step = 1f
    private var low = 0
    private var high = 0
    private var ready = false
    private var start: () -> Unit = {}
    private var change: (Float) -> Unit = {}
    private var end: () -> Unit = {}
    private var downX = 0f
    private var downY = 0f
    private val interceptSlop = ViewConfiguration.get(context).scaledTouchSlop * 3 / 4

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            Glide.stop()
            stop()
            downX = e.rawX
            downY = e.rawY
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            begin()
            move(position + dx)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) <= 300f) return false
            begin()
            scroller.fling(position.toInt(), 0, -vx.toInt(), 0, Int.MIN_VALUE, Int.MAX_VALUE, 0, 0)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var reachedLimit = false
                while (scroller.computeScrollOffset()) {
                    val wanted = scroller.currX.toFloat()
                    Glide.step { move(wanted) }
                    reachedLimit = position <= low.toFloat() * spacing || position >= high.toFloat() * spacing
                    if (reachedLimit || !tracking) break
                    withFrameNanos { }
                }
                if (!reachedLimit) Glide.step { settle() }
                Glide.finished(fling)
                fling = null
                finish()
            }
            fling = job
            Glide.start(job) { stop() }
            job.start()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    fun configure(value: Float, step: Float, min: Float, max: Float, enabled: Boolean,
                  onStart: () -> Unit, onValue: (Float) -> Unit, onEnd: () -> Unit) {
        start = onStart; change = onValue; end = onEnd
        this.step = step.takeIf { it > 0f } ?: 1f
        low = tick(min); high = tick(max)
        isEnabled = enabled
        val external = tick(value)
        if (!ready || (!tracking && external != lastTick)) {
            position = external.toFloat() * spacing
            lastTick = external
            ready = true
            settle(notify = false)
        }
        if (!enabled) stop()
        invalidate()
    }

    private fun tick(value: Float): Int = (value / step).roundToInt()

    private fun begin() {
        if (!tracking) { tracking = true; start() }
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun move(pixels: Float, notify: Boolean = true) {
        position = pixels.coerceIn(low.toFloat() * spacing, high.toFloat() * spacing)
        val next = (position / spacing).roundToInt().coerceIn(low, high)
        if (next != lastTick) {
            lastTick = next
            if (notify) { begin(); change(next * step) }
        }
        invalidate()
    }

    private fun settle(notify: Boolean = true) {
        val snapped = floor((position + spacing / 2) / spacing) * spacing - spacing / 2
        move(snapped, notify)
    }

    private fun finish() {
        if (tracking) { tracking = false; end() }
    }

    fun stop() {
        scroller.forceFinished(true)
        if (fling != null) Glide.finished(fling)
        fling?.cancel()
        fling = null
        finish()
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.rawX - downX) > interceptSlop &&
            abs(event.rawX - downX) > abs(event.rawY - downY)) parent?.requestDisallowInterceptTouchEvent(true)
        detector.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && fling == null) {
            settle()
            finish()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val half = width / 2f
        val centerTick = width / spacing / 2
        val phase = -phase(spacing * 5)
        val first = ((position + phase) / spacing).roundToInt() - centerTick
        for (i in -5..(width / spacing + 5)) {
            val index = first.toLong() + i
            if (index < low || index > high) continue
            val x = phase + i * spacing
            val alpha = (255 * (1f - abs(x - half) / half)).toInt().coerceIn(0, 255)
            paint.color = if (index % 5 == 0L) Am.OnDark.toArgb() else Am.Caption.toArgb()
            paint.alpha = alpha
            paint.strokeWidth = tickWidth
            canvas.drawLine(x, inset, x, height - inset, paint)
        }
        val needle = centerTick * spacing - phase(spacing)
        paint.color = Am.AccentGlow.toArgb()
        paint.strokeWidth = needleWidth
        canvas.drawLine(needle, 0f, needle, height.toFloat(), paint)
    }

    private fun phase(period: Int): Float {
        val offset = (position + spacing / 2) % period
        return if (offset < 0) offset + period else offset
    }
}
