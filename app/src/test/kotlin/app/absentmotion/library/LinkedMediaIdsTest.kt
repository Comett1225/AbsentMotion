package app.absentmotion.library

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkedMediaIdsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun repeatedLinkedElementsHaveSeparateMediaLayerIds() {
        val store = ProjectStore(tmp.newFolder())
        val element = Scene.parse("""<scene type="element" width="100" height="100" fps="30"><shape id="1" startTime="0" endTime="999" fillType="media" fillVideo="clip"/></scene>""")
        val id = store.createElement(element)
        val link = store.linkOf(id)
        val parent = Scene.parse("""<scene width="100" height="100" fps="30"><embedScene id="10" link="$link"><scene/></embedScene><embedScene id="20" link="$link"><scene/></embedScene></scene>""")
        val loaded = store.load(store.create(parent))!!
        val childIds = loaded.layers.map { it.nested!!.layers.single().id }
        assertEquals(2, childIds.toSet().size)
        assertEquals(listOf(10L, 20L), loaded.layers.map { it.id })
    }
}
