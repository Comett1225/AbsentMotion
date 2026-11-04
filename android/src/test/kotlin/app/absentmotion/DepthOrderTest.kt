package app.absentmotion

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthOrderTest {
    private fun Scene.stable() = node.with("modifiedTime", null).toXml()

    @Test
    fun defaultsAndRoundTrip() {
        val old = Scene.parse("<scene width=\"100\" height=\"100\"/>")
        assertFalse(old.depthOrder)
        assertTrue(Scene.parse(old.withDepthOrder(true).toXml()).depthOrder)
        assertFalse(Scene.parse(old.withDepthOrder(true).withDepthOrder(false).toXml()).depthOrder)
        assertTrue(Scene.create("test", 100, 100, 30, 0).depthOrder)
    }

    @Test
    fun oldAndNewProjectsSortByLayer() {
        val old = Scene.parse("<scene width=\"100\" height=\"100\" depthOrder=\"true\"><shape id=\"1\" /></scene>")
        assertFalse(old.pieceOrder)
        assertTrue(old.layers[0].mixesPieces)
        assertFalse(old.mixing(old.layers[0]))
        assertEquals(old.stable(), Scene.parse(old.toXml()).stable())
        assertFalse(Scene.create("test", 100, 100, 30, 0).pieceOrder)
    }

    @Test
    fun pieceSortingRoundTrip() {
        val old = Scene.parse("<scene width=\"100\" height=\"100\" depthOrder=\"true\"><shape id=\"1\" /><shape id=\"2\" /></scene>")
        val on = Scene.parse(old.withPieceOrder(true).toXml())
        assertTrue(on.pieceOrder)
        assertEquals("piece", on.node["depthSort"])
        assertTrue(on.layers.all { on.mixing(it) })
        val back = Scene.parse(on.withPieceOrder(false).toXml())
        assertNull(back.node["depthSort"])
        assertEquals(old.stable(), back.stable())
    }

    @Test
    fun layerKeepsWholeSortingWhenTurnedOff() {
        val on = Scene.parse("<scene width=\"100\" height=\"100\" depthOrder=\"true\" depthSort=\"piece\"><shape id=\"1\" /><shape id=\"2\" /></scene>")
        val off = Scene.parse(on.update(1) { it.withMixPieces(false) }.toXml())
        assertEquals("false", off.layer(1)!!.node["mixPieces"])
        assertFalse(off.mixing(off.layer(1)!!))
        assertTrue(off.mixing(off.layer(2)!!))
        val again = Scene.parse(off.update(1) { it.withMixPieces(true) }.toXml())
        assertNull(again.layer(1)!!.node["mixPieces"])
        assertEquals(on.layer(1)!!.node, again.layer(1)!!.node)
    }

    @Test
    fun mixingFromALayerTurnsOnPieceSorting() {
        val flat = Scene.parse("<scene width=\"100\" height=\"100\"><shape id=\"1\" mixPieces=\"false\" /><shape id=\"2\" /></scene>")
        val mixed = Scene.parse(flat.withMixing(1, true).toXml())
        assertTrue(mixed.depthOrder)
        assertTrue(mixed.pieceOrder)
        assertTrue(mixed.mixing(mixed.layer(1)!!))
        val stopped = Scene.parse(mixed.withMixing(1, false).toXml())
        assertTrue(stopped.pieceOrder)
        assertFalse(stopped.mixing(stopped.layer(1)!!))
        assertTrue(stopped.mixing(stopped.layer(2)!!))
    }
}
