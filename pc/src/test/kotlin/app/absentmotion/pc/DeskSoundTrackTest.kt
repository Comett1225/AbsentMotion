package app.absentmotion.pc

import android.content.Context
import app.absentmotion.audio.OfflineMix
import org.junit.Assert.assertFalse
import org.junit.Test

class DeskSoundTrackTest {
    @Test fun blockedOrForeignMediaHasNoSound() {
        assertFalse(OfflineMix.hasSoundTrack(Context(), "blocked-file:///tmp/clip.mp4"))
        assertFalse(OfflineMix.hasSoundTrack(Context(), "https://example.com/clip.mp4"))
    }
}
