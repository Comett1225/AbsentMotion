package app.absentmotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedMediaTest {
    @Test fun mediaMimeTypesSelectThePickerPath() {
        assertEquals(SharedMediaKind.Video, sharedMediaKind("clip", "video/mp4"))
        assertEquals(SharedMediaKind.Picture, sharedMediaKind("picture.jpg", "image/jpeg"))
        assertEquals(SharedMediaKind.Picture, sharedMediaKind("drawing.svg", "image/svg+xml"))
        assertEquals(SharedMediaKind.Sound, sharedMediaKind(null, "AUDIO/WAV"))
    }

    @Test fun projectNamesTakePrecedenceOverMediaMimeTypes() {
        for (extension in listOf("absproj", "amproj", "fla", "XML", "zip")) {
            assertNull(sharedMediaKind("sample.$extension", "image/png"))
        }
    }

    @Test fun projectAndUnspecifiedTypesStayWithTheImporter() {
        assertNull(sharedMediaKind("scene", "text/xml"))
        assertNull(sharedMediaKind("bundle", "application/zip"))
        assertNull(sharedMediaKind("bundle", "application/octet-stream"))
        assertNull(sharedMediaKind("scene", null))
        assertNull(sharedMediaKind("other", "text/plain"))
    }

    @Test fun vagueReportedTypesFallBackToTheFileName() {
        assertEquals("video/mp4", sharedMime("GTA clip.MP4", "application/octet-stream"))
        assertEquals("audio/ogg", sharedMime("song.ogg", null))
        assertEquals("audio/ogg", sharedMime("noext", "application/ogg"))
        assertEquals("audio/flac", sharedMime("noext", "application/x-flac"))
        assertEquals("image/png", sharedMime("shot.png", "image/png"))
        assertEquals("video/webm", sharedMime("noext", "video/webm"))
        assertNull(sharedMime("scene.absproj", "application/octet-stream"))
        assertNull(sharedMime("noext", null))
    }
}
