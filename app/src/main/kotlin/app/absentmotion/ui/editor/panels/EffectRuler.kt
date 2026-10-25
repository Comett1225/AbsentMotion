package app.absentmotion.ui.editor.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import app.absentmotion.editor.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import app.absentmotion.ui.theme.Am

@Composable
internal fun EffectRuler(
    value: Float, step: Float, min: Float, max: Float, enabled: Boolean,
    onStart: () -> Unit, onValue: (Float) -> Unit, onEnd: () -> Unit,
    modifier: Modifier = Modifier, default: Float? = null, vibrate: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    key(LocalDensity.current.density) {
        AndroidView(modifier = modifier, factory = { EffectRulerView(it, scope) },
            onRelease = { it.stop() },
            update = { it.configure(value, step, min, max, enabled, onStart, onValue, onEnd, default, vibrate) })
    }
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
    private var fine = false
    private var fineX = 0f
    private var reset: Float? = null
    private var vibrate = true
    private var tapUp = 0L
    private var resetting = false
    private val interceptSlop = ViewConfiguration.get(context).scaledTouchSlop * 3 / 4

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            Glide.stop()
            stop()
            downX = e.rawX
            downY = e.rawY
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!isEnabled) return
            fine = true
            fineX = e.x
            begin()
            if (vibrate) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
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
    })

    fun configure(value: Float, step: Float, min: Float, max: Float, enabled: Boolean,
                  onStart: () -> Unit, onValue: (Float) -> Unit, onEnd: () -> Unit, default: Float? = null, vibrate: Boolean = true) {
        this.vibrate = vibrate
        start = onStart; change = onValue; end = onEnd; reset = default
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
        val snapped = rulerSnap(position, spacing)
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

    override fun getAccessibilityClassName(): CharSequence = android.widget.SeekBar::class.java.name

    @Suppress("DEPRECATION")
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, low * step, high * step, lastTick * step)
        if (!isEnabled) return
        if (lastTick > low) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (lastTick < high) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val target = (if (!isEnabled) null else when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> rulerAccessibleTick(lastTick, low, high, 1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> rulerAccessibleTick(lastTick, low, high, -1)
            android.R.id.accessibilityActionSetProgress -> arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.let { tick(it).coerceIn(low, high) }
            else -> null
        }) ?: return super.performAccessibilityAction(action, arguments)
        stop()
        start()
        position = target.toFloat() * spacing
        lastTick = target
        change(target * step)
        end()
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) resetting = false
        else if (resetting) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) resetting = false
            return true
        }
        val value = reset
        if (event.actionMasked == MotionEvent.ACTION_DOWN && value != null && event.eventTime - tapUp < ViewConfiguration.getDoubleTapTimeout()) {
            tapUp = 0L
            resetting = true
            stop()
            start()
            change(value.coerceIn(low * step, high * step))
            end()
            if (vibrate) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.rawX - downX) > interceptSlop &&
            abs(event.rawX - downX) > abs(event.rawY - downY)) parent?.requestDisallowInterceptTouchEvent(true)
        if (fine && event.actionMasked == MotionEvent.ACTION_MOVE) {
            move(position + (fineX - event.x) * FINE_FACTOR)
            fineX = event.x
        }
        detector.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && fling == null) {
            if (fine) { fine = false; invalidate() }
            val dragged = tracking
            tapUp = if (!dragged && event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) event.eventTime else 0L
            settle()
            finish()
            if (dragged) rulerTip(context)
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
        paint.color = (if (fine) Am.Gold else Am.AccentGlow).toArgb()
        paint.strokeWidth = if (fine) needleWidth * 2 else needleWidth
        canvas.drawLine(needle, 0f, needle, height.toFloat(), paint)
    }

    private fun phase(period: Int): Float {
        val offset = (position + spacing / 2f) % period
        return if (offset < 0) offset + period else offset
    }
}

internal fun rulerAccessibleTick(tick: Int, low: Int, high: Int, direction: Int): Int =
    (tick + direction * maxOf(1, (high - low) / 20)).coerceIn(low, high)

internal fun rulerSnap(position: Float, spacing: Int): Float =
    (position / spacing).roundToInt() * spacing.toFloat()
