package app.absentmotion.ui.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.videoTimeUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class VideoThumbnailTimesTest {
    private val scene = Scene.parse(
        """<scene width="1080" height="1920" fps="30" totalTime="9999">
          <shape id="1" startTime="1000" endTime="9999" fillType="media" fillVideo="file:/v.mp4" inTime="0" outTime="20000" speed="1.000000" mediaFillMode="fill" s=".rect">
            <transform><location value="540,960,0"/></transform>
          </shape>
        </scene>""",
    )
    private val layer = scene.layers.single()

    @Test fun tilesCoverTheVisibleWindowAtTheirVideoTimes() {
        val tiles = thumbnailTimes(layer, scene, 0f, 3500f, 1000f)
        assertEquals(listOf(1000, 2000, 3000), tiles.map { it.first })
        assertEquals(tiles.map { layer.videoTimeUs(it.first, scene) }, tiles.map { it.second })
        assertEquals(listOf(3000, 4000), thumbnailTimes(layer, scene, 3200f, 4100f, 1000f).map { it.first })
    }

    @Test fun oneTimingIsReusedForAsLongAsTheLayerNodeIsTheSame() {
        assertSame(timingOf(layer), timingOf(layer))
        assertSame(timingOf(layer), timingOf(scene.update(layer.id) { it }.layers.single()))
        val faster = layer.copy(node = layer.node.with("speed", "2.000000"))
        assertNotSame(timingOf(layer), timingOf(faster))
        val tiles = thumbnailTimes(faster, scene, 0f, 9999f, 400f)
        assertEquals(tiles.map { faster.videoTimeUs(it.first, scene) }, tiles.map { it.second })
    }

    @Test fun movingTheLayerKeepsTheThumbnailsButRetimingChangesThem() {
        val tiles = thumbnailTimes(layer, scene, 0f, 9999f, 400f)
        val moved = scene.update(layer.id) { it.withTransform("location", 100f, 200f, 0f) }
        assertEquals(tiles, thumbnailTimes(moved.layers.single(), moved, 0f, 9999f, 400f))
        val faster = Scene(scene.node.copy(children = listOf(layer.node.with("speed", "2.000000"))))
        assertNotEquals(tiles, thumbnailTimes(faster.layers.single(), faster, 0f, 9999f, 400f))
    }
}
