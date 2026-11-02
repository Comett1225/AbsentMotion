package app.absentmotion.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLinksTest {
    private fun kind(text: String) = findMediaLink(text)?.let { it.site to it.kind }

    @Test
    fun recognisesCommonLinks() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to (LinkSite.YouTube to LinkKind.Video),
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s" to (LinkSite.YouTube to LinkKind.Video),
            "https://youtu.be/dQw4w9WgXcQ?si=abc" to (LinkSite.YouTube to LinkKind.Video),
            "https://youtube.com/shorts/aBcDeFgHiJk" to (LinkSite.YouTube to LinkKind.Short),
            "https://www.youtube.com/live/aBcDeFgHiJk" to (LinkSite.YouTube to LinkKind.Live),
            "https://www.youtube.com/playlist?list=PL123" to (LinkSite.YouTube to LinkKind.Playlist),
            "https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=xyz" to (LinkSite.Instagram to LinkKind.Reel),
            "https://www.instagram.com/someone/reel/C1a2B3c4D5e/" to (LinkSite.Instagram to LinkKind.Reel),
            "https://www.instagram.com/p/C1a2B3c4D5e/" to (LinkSite.Instagram to LinkKind.Photos),
            "https://www.instagram.com/stories/someone/1234567890/" to (LinkSite.Instagram to LinkKind.Story),
            "https://www.tiktok.com/@someone/video/7312345678901234567" to (LinkSite.TikTok to LinkKind.Video),
            "https://www.tiktok.com/@someone/photo/7312345678901234567" to (LinkSite.TikTok to LinkKind.Photos),
            "https://vm.tiktok.com/ZMabc123/" to (LinkSite.TikTok to LinkKind.Video),
            "https://x.com/someone/status/1790000000000000000" to (LinkSite.X to LinkKind.Post),
            "https://twitter.com/someone/status/1790000000000000000?s=20" to (LinkSite.X to LinkKind.Post),
            "https://www.facebook.com/watch?v=123456789" to (LinkSite.Facebook to LinkKind.Video),
            "https://www.facebook.com/share/r/abc123/" to (LinkSite.Facebook to LinkKind.Reel),
            "https://fb.watch/abc123/" to (LinkSite.Facebook to LinkKind.Video),
            "https://www.reddit.com/r/videos/comments/abc123/a_title/" to (LinkSite.Reddit to LinkKind.Post),
            "https://v.redd.it/abc123" to (LinkSite.Reddit to LinkKind.Video),
            "https://vimeo.com/76979871" to (LinkSite.Vimeo to LinkKind.Video),
            "https://clips.twitch.tv/SomeClipName" to (LinkSite.Twitch to LinkKind.Clip),
            "https://www.dailymotion.com/video/x8abc12" to (LinkSite.Dailymotion to LinkKind.Video),
            "https://www.threads.net/@someone/post/C1a2B3c4D5e" to (LinkSite.Threads to LinkKind.Post),
            "https://bsky.app/profile/someone.bsky.social/post/3kabc" to (LinkSite.Bluesky to LinkKind.Post),
        )
        for ((link, expected) in cases) assertEquals(link, expected, kind(link))
    }

    @Test
    fun findsTheLinkInsideSharedText() {
        val found = findMediaLink("Look at this! https://youtube.com/shorts/aBcDeFgHiJk?feature=share so funny")
        assertEquals(LinkKind.Short, found?.kind)
        assertEquals("https://youtube.com/shorts/aBcDeFgHiJk?feature=share", found?.url)
        assertEquals(LinkSite.Instagram to LinkKind.Reel, kind("instagram.com/reel/C1a2B3c4D5e"))
        assertEquals("https://instagram.com/reel/C1a2B3c4D5e", findMediaLink("instagram.com/reel/C1a2B3c4D5e")?.url)
        assertEquals(LinkSite.TikTok to LinkKind.Video, kind("(https://www.tiktok.com/t/ZTabc123/)"))
    }

    @Test
    fun ignoresWhatItDoesNotKnow() {
        for (text in listOf(
            "", "hello there", "https://example.com/watch?v=abc", "https://youtube.com.evil.example/watch?v=abc",
            "https://notyoutube.com/watch?v=abc", "https://www.youtube.com/@someone", "https://www.instagram.com/someone/",
            "https://x.com/someone", "ftp://youtube.com/watch?v=abc", "javascript:alert(1)", "https://www.tiktok.com/@someone",
        )) assertNull(text, findMediaLink(text))
    }

    @Test
    fun picturePostsAreTheOnlyKindWithoutOptions() {
        assertTrue(LinkKind.Photos.pictures)
        assertEquals(listOf(LinkKind.Photos), LinkKind.entries.filter { it.pictures })
    }
}
