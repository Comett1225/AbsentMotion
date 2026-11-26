package app.absentmotion.editor

import app.absentmotion.media.FaceSpot
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.sceneOf
import app.absentmotion.videoNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FaceTrackTest {
    private val effect = XmlNode("effect", mapOf("id" to FACE_EFFECT, "hidden" to "false"), listOf(
        XmlNode("property", mapOf("name" to "zoom", "type" to "float", "value" to "1")),
        XmlNode("property", mapOf("name" to "position", "type" to "vec2", "value" to "0.5,0.5")),
        XmlNode("property", mapOf("name" to "smoothing", "type" to "float", "value" to "0.3")),
    ))

    private fun scene(end: Int = 1999, uri: String = "v.mp4"): Scene =
        sceneOf(Layer(videoNode(0, end, 1, inTime = 0, outTime = 4000, uri = uri, attrs = mapOf("s" to ".rect"), children = listOf(effect))).node, width = 1080, height = 1920)

    private val stamps = (0 until 120).map { it * 33_333L }

    private fun moving(pts: Long) = FaceSpot(200f + pts / 10_000f, 300f, 80f)

    private suspend fun tracked(pick: FacePick, frames: Map<Long, List<FaceSpot>>, reads: MutableList<Long> = ArrayList()) =
        followFace(pick, frames.keys.toList(), { batch, deliver -> batch.forEach { reads += it; deliver(it, frames[it].orEmpty()) } })

    private fun traced(s: Scene): Scene {
        val faces = stamps.associateWith { listOf(moving(it), FaceSpot(900f, 300f, 80f)) }
        val samples = runBlocking { tracked(FacePick(0, moving(0)), faces) }
        val source = FaceSource("v.mp4", 1920, 1080, FacePick(0, moving(0)), stamps.first(), stamps.last(), samples)
        return s.withFaceSource(1, 0, source)
    }

    private val Scene.face: XmlNode get() = layer(1)!!.effects[0]

    @Test fun matchingKeepsTheNearestFaceOfASimilarSize() {
        val last = FaceSpot(100f, 100f, 50f)
        assertEquals(120f, matchFace(last, 1, listOf(FaceSpot(300f, 100f, 50f), FaceSpot(120f, 100f, 52f)))!!.x)
        assertNull(matchFace(last, 1, listOf(FaceSpot(105f, 100f, 200f))))
        assertNull(matchFace(last, 1, listOf(FaceSpot(200f, 100f, 50f))))
        assertEquals(200f, matchFace(last, 6, listOf(FaceSpot(200f, 100f, 50f)))!!.x)
    }

    @Test fun followingStaysOnThePickedFaceThroughGapsAndBothWays() = runBlocking {
        val faces = stamps.associateWith { p -> if (p in 1_000_000L..1_100_000L) emptyList() else listOf(moving(p), FaceSpot(900f, 300f, 80f)) }
        val pick = FacePick(stamps[60], moving(stamps[60]))
        val reads = ArrayList<Long>()
        val samples = tracked(pick, faces, reads)
        assertEquals(stamps.size - faces.values.count { it.isEmpty() }, samples.size)
        assertTrue(samples.all { (p, s) -> abs(s.x - moving(p).x) < 1e-3f })
        assertEquals(samples.map { it.first }.sorted(), samples.map { it.first })
        assertTrue(reads.containsAll(stamps))
    }

    @Test fun pickingTheOtherFaceFollowsThatOne() = runBlocking {
        val faces = stamps.associateWith { listOf(moving(it), FaceSpot(900f, 300f, 80f)) }
        val samples = tracked(FacePick(0, FaceSpot(900f, 300f, 80f)), faces)
        assertTrue(samples.all { it.second.x == 900f })
    }

    @Test fun smoothingAveragesNoiseAndKeepsAStillFace() {
        val still = List(30) { floatArrayOf(0.5f, 0.5f, 0.1f) }
        assertTrue(smoothFace(still, FloatArray(30) { 6f }).all { abs(it[0] - 0.5f) < 1e-6f && abs(it[2] - 0.1f) < 1e-6f })
        val noisy = List(60) { floatArrayOf(if (it % 2 == 0) 0.4f else 0.6f, 0.5f, 0.1f) }
        val smooth = smoothFace(noisy, FloatArray(60) { 4f })
        assertTrue(smooth.subList(10, 50).all { abs(it[0] - 0.5f) < 0.01f })
        assertSame(noisy[3], smoothFace(noisy, FloatArray(60))[3])
    }

    @Test fun aTrackIsStoredWithItsSourceAndAPathTheEngineReads() {
        val s = traced(scene())
        val path = s.face.child("facepath")!!
        assertEquals("v.mp4", path["uri"])
        assertEquals(s.faceKey(s.layer(1)!!, s.face), path["key"])
        assertEquals(FaceState.Ready, s.faceState(s.layer(1)!!, s.face))
        val keys = path["path"]!!.split(' ').map { k -> k.split(',').map { it.toFloat() } }
        assertTrue(keys.size in 2..60)
        assertTrue(keys.zipWithNext().all { (a, b) -> a[0] < b[0] && b[1] > a[1] })
        assertEquals(stamps.size, s.face.faceSource()!!.samples.size)
    }

    @Test fun zoomAndPositionChangesKeepTheTrackAsItIs() {
        val s = traced(scene())
        val zoomed = s.update(1) { l -> l.withEffect(0) { it.withEffectProperty("zoom", "float", "2", 0f, 0f..0f).withEffectProperty("position", "vec2", "0.3,0.4", 0f, 0f..0f) } }
        val followed = zoomed.withFacePathsFollowing()
        assertSame(zoomed.node, followed.node)
        assertSame(s.face.child("facepath"), followed.face.child("facepath"))
    }

    @Test fun smoothingAndTrimsRedrawThePathWithoutTrackingAgain() {
        val s = traced(scene())
        val before = s.face.child("facepath")!!
        val smoother = s.update(1) { l -> l.withEffect(0) { it.withEffectProperty("smoothing", "float", "0.9", 0f, 0f..0f) } }.withFacePathsFollowing()
        val after = smoother.face.child("facepath")!!
        assertEquals(before["src"], after["src"])
        assertEquals(before["pick"], after["pick"])
        assertNotEquals(before["path"], after["path"])
        assertNotEquals(before["key"], after["key"])
        val trimmed = s.update(1) { it.copy(node = it.node.with("endTime", "999")) }.withFacePathsFollowing()
        assertEquals(before["src"], trimmed.face.child("facepath")!!["src"])
        assertEquals(FaceState.Ready, trimmed.faceState(trimmed.layer(1)!!, trimmed.face))
        val longer = s.update(1) { it.copy(node = it.node.with("endTime", "5999").with("outTime", "8000")) }.withFacePathsFollowing()
        assertEquals(FaceState.Partial, longer.faceState(longer.layer(1)!!, longer.face))
    }

    @Test fun otherMediaOrARepickStartsFresh() {
        val s = traced(scene())
        val replaced = s.update(1) { it.copy(node = it.node.with("fillVideo", "other.mp4")) }.withFacePathsFollowing()
        assertNull(replaced.face.child("facepath"))
        assertEquals(FaceState.NoFace, replaced.faceState(replaced.layer(1)!!, replaced.face))
        assertEquals(FaceState.NoFace, scene().faceState(scene().layer(1)!!, effect))
        val faces = stamps.associateWith { listOf(moving(it), FaceSpot(900f, 300f, 80f)) }
        val repicked = s.withFaceSource(1, 0, FaceSource("v.mp4", 1920, 1080, FacePick(0, FaceSpot(900f, 300f, 80f)), 0, stamps.last(),
            runBlocking { tracked(FacePick(0, FaceSpot(900f, 300f, 80f)), faces) }))
        assertNotEquals(s.face.child("facepath")!!["src"], repicked.face.child("facepath")!!["src"])
        assertTrue(repicked.face.faceSource()!!.samples.all { abs(it.second.x - 900f) < 0.1f })
    }
}
