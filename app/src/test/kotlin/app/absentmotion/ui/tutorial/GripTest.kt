package app.absentmotion.ui.tutorial

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GripTest {
    private val published = ArrayList<Boolean>()
    private val watch = GripWatch { published += it }

    private fun pointerUp(index: Int) = MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerDown(index: Int) = MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test fun oneFingerIsHeldFromTheFirstTouchUntilItLifts() {
        assertFalse(watch.held)
        watch.touch(MotionEvent.ACTION_DOWN)
        assertTrue(watch.held)
        repeat(5) { watch.touch(MotionEvent.ACTION_MOVE) }
        assertTrue(watch.held)
        watch.touch(MotionEvent.ACTION_UP)
        assertFalse(watch.held)
        assertEquals(listOf(true, false), published)
    }

    @Test fun aPinchStaysHeldUntilTheLastFingerLifts() {
        watch.touch(MotionEvent.ACTION_DOWN)
        watch.touch(pointerDown(1))
        watch.touch(MotionEvent.ACTION_MOVE)
        watch.touch(pointerUp(0))
        assertTrue(watch.held)
        watch.touch(MotionEvent.ACTION_MOVE)
        assertTrue(watch.held)
        watch.touch(MotionEvent.ACTION_UP)
        assertFalse(watch.held)
        assertEquals(listOf(true, false), published)
    }

    @Test fun aCancelReleasesTheHold() {
        watch.touch(MotionEvent.ACTION_DOWN)
        watch.touch(MotionEvent.ACTION_MOVE)
        watch.touch(MotionEvent.ACTION_CANCEL)
        assertFalse(watch.held)
        assertEquals(listOf(true, false), published)
    }

    @Test fun losingTheWindowOrTheEditorReleasesTheHoldOnce() {
        watch.touch(MotionEvent.ACTION_DOWN)
        watch.interrupt()
        watch.interrupt()
        assertFalse(watch.held)
        assertEquals(listOf(true, false), published)
    }

    @Test fun aGestureAlreadyRunningWhenWatchingStartsIsStillSeenThroughItsEnd() {
        watch.touch(MotionEvent.ACTION_MOVE)
        assertTrue(watch.held)
        watch.touch(MotionEvent.ACTION_UP)
        assertFalse(watch.held)
    }

    @Test fun eventsThatAreNotATouchStreamChangeNothing() {
        watch.touch(MotionEvent.ACTION_OUTSIDE)
        watch.touch(MotionEvent.ACTION_HOVER_MOVE)
        watch.touch(MotionEvent.ACTION_SCROLL)
        assertFalse(watch.held)
        assertEquals(emptyList<Boolean>(), published)
        watch.touch(MotionEvent.ACTION_DOWN)
        watch.touch(MotionEvent.ACTION_HOVER_EXIT)
        assertTrue(watch.held)
    }

    @Test fun repeatedMovesPublishNothingNew() {
        watch.touch(MotionEvent.ACTION_DOWN)
        repeat(20) { watch.touch(MotionEvent.ACTION_MOVE) }
        assertEquals(listOf(true), published)
    }
}
