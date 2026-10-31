package app.absentmotion.tools

import java.net.URI

enum class LinkSite(val title: String, vararg val hosts: String) {
    YouTube("YouTube", "youtube.com", "youtu.be", "youtube-nocookie.com"),
    Instagram("Instagram", "instagram.com", "instagr.am"),
    TikTok("TikTok", "tiktok.com"),
    X("X", "x.com", "twitter.com"),
    Facebook("Facebook", "facebook.com", "fb.watch", "fb.com"),
    Reddit("Reddit", "reddit.com", "redd.it"),
    Vimeo("Vimeo", "vimeo.com"),
    Twitch("Twitch", "twitch.tv"),
    Dailymotion("Dailymotion", "dailymotion.com", "dai.ly"),
    Pinterest("Pinterest", "pinterest.com", "pin.it"),
    Threads("Threads", "threads.net", "threads.com"),
    Bluesky("Bluesky", "bsky.app"),
    Snapchat("Snapchat", "snapchat.com"),
}

enum class LinkKind(val pictures: Boolean = false) { Video, Short, Reel, Live, Story, Clip, Playlist, Post, Photos(true) }

data class MediaLink(val url: String, val site: LinkSite, val kind: LinkKind)

fun findMediaLink(text: String): MediaLink? = text.split(Regex("\\s+")).firstNotNullOfOrNull(::mediaLink)

fun mediaLink(token: String): MediaLink? {
    val raw = token.trim().trim('<', '>', '(', ')', '[', ']', '{', '}', '"', '\'', ',', ';', '!')
    if (raw.isEmpty() || '.' !in raw) return null
    val url = if ("://" in raw) raw else "https://$raw"
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val site = LinkSite.entries.firstOrNull { s -> s.hosts.any { host == it || host.endsWith(".$it") } } ?: return null
    val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    val query = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }.toSet()
    val kind = kindOf(site, host, parts, query) ?: return null
    return MediaLink(url, site, kind)
}

private fun List<String>.after(name: String): Boolean = indexOf(name).let { it >= 0 && it + 1 < size }

private fun kindOf(site: LinkSite, host: String, parts: List<String>, query: Set<String>): LinkKind? {
    val first = parts.firstOrNull()
    return when (site) {
        LinkSite.YouTube -> when {
            host == "youtu.be" -> LinkKind.Video.takeIf { parts.isNotEmpty() }
            parts.after("shorts") -> LinkKind.Short
            parts.after("live") -> LinkKind.Live
            first == "watch" && "v" in query -> LinkKind.Video
            parts.after("embed") || parts.after("v") -> LinkKind.Video
            first == "playlist" && "list" in query -> LinkKind.Playlist
            else -> null
        }
        LinkSite.Instagram -> when {
            first == "stories" && parts.size >= 3 -> LinkKind.Story
            parts.after("reel") || parts.after("reels") -> LinkKind.Reel
            parts.after("tv") -> LinkKind.Video
            parts.after("p") -> LinkKind.Photos
            else -> null
        }
        LinkSite.TikTok -> when {
            host.startsWith("vm.") || host.startsWith("vt.") -> LinkKind.Video.takeIf { parts.isNotEmpty() }
            parts.after("photo") -> LinkKind.Photos
            parts.after("video") || first == "t" && parts.size >= 2 -> LinkKind.Video
            else -> null
        }
        LinkSite.X -> LinkKind.Post.takeIf { parts.indexOf("status") >= 1 && parts.after("status") }
        LinkSite.Facebook -> when {
            host == "fb.watch" -> LinkKind.Video.takeIf { parts.isNotEmpty() }
            first == "watch" && "v" in query -> LinkKind.Video
            first == "share" && parts.size >= 3 -> when (parts[1]) { "v" -> LinkKind.Video; "r" -> LinkKind.Reel; else -> LinkKind.Post }
            parts.after("reel") -> LinkKind.Reel
            parts.after("videos") -> LinkKind.Video
            parts.after("posts") || first == "story.php" -> LinkKind.Post
            first == "photo" || first == "photo.php" -> LinkKind.Photos
            else -> null
        }
        LinkSite.Reddit -> when {
            host == "v.redd.it" -> LinkKind.Video.takeIf { parts.isNotEmpty() }
            host == "redd.it" -> LinkKind.Post.takeIf { parts.isNotEmpty() }
            first == "r" && (parts.after("comments") || parts.after("s")) -> LinkKind.Post
            else -> null
        }
        LinkSite.Vimeo -> LinkKind.Video.takeIf { parts.any { p -> p.all(Char::isDigit) } }
        LinkSite.Twitch -> when {
            host == "clips.twitch.tv" -> LinkKind.Clip.takeIf { parts.isNotEmpty() }
            parts.after("clip") -> LinkKind.Clip
            first == "videos" && parts.size >= 2 -> LinkKind.Video
            else -> null
        }
        LinkSite.Dailymotion -> when {
            host == "dai.ly" -> LinkKind.Video.takeIf { parts.isNotEmpty() }
            parts.after("video") -> LinkKind.Video
            else -> null
        }
        LinkSite.Pinterest -> when {
            host == "pin.it" -> LinkKind.Post.takeIf { parts.isNotEmpty() }
            parts.after("pin") -> LinkKind.Post
            else -> null
        }
        LinkSite.Threads -> LinkKind.Post.takeIf { first?.startsWith("@") == true && parts.after("post") }
        LinkSite.Bluesky -> LinkKind.Post.takeIf { first == "profile" && parts.after("post") }
        LinkSite.Snapchat -> LinkKind.Video.takeIf { parts.after("spotlight") }
    }
}
