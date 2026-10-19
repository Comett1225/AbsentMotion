package app.absentmotion.editor

import app.absentmotion.audio.AudioClip
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractAudioTest {
    private fun video(gain: String) = Scene.parse("""<scene width="100" height="100" fps="30">
        <shape id="1" startTime="0" endTime="1999" fillType="media" fillVideo="content://v" outTime="2000">$gain</shape></scene>""").layer(1)!!

    private fun audible(gain: String) = AudioClip.of(Scene.parse("""<scene width="100" height="100" fps="30"/>""").add(video(gain).extractedAudio(2)!!))

    @Test fun aMutedVideoGivesAnAudibleAudioLayer() {
        assertNull(video("""<gain value="0.000000"/>""").extractedAudio(2)!!.node.child("gain"))
        assertEquals(listOf(2L), audible("""<gain value="0.000000"/>""").map { it.id })
    }

    @Test fun aFadeAndAVolumeAreKept() {
        val fade = """<gain><kf t="0" v="0"/><kf t="1" v="1"/></gain>"""
        assertEquals(video(fade).node.child("gain"), video(fade).extractedAudio(2)!!.node.child("gain"))
        assertEquals(listOf(2L), audible(fade).map { it.id })
        assertEquals("0.5", video("""<gain value="0.5"/>""").extractedAudio(2)!!.node.child("gain")!!["value"])
    }

    @Test fun onlyVideosGiveAudio() {
        assertNull(Scene.parse("""<scene><shape id="1" fillType="media" fillImage="content://p"/></scene>""").layer(1)!!.extractedAudio(2))
    }
}
